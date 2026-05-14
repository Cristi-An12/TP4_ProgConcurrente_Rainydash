package com.rainydash.simulation;

import com.rainydash.dto.SimulationStateDTO;
import com.rainydash.model.Order;
import com.rainydash.model.Rider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * SimulationService — orchestrates producers, consumers, and the shared buffer.
 *
 * Responsibilities:
 *  - Start/pause/stop simulation
 *  - Spawn and shut down producer/consumer thread pools
 *  - Run a stale-order cancellation background task
 *  - Broadcast SimulationStateDTO over WebSocket every 500 ms
 */
@Service
public class SimulationService {

    private static final Logger log = LoggerFactory.getLogger(SimulationService.class);

    // ── Config ─────────────────────────────────────────────────────────────
    @Value("${simulation.buffer.capacity:10}")
    private int bufferCapacity;

    @Value("${simulation.producers.initial:3}")
    private int initialProducers;

    @Value("${simulation.consumers.initial:2}")
    private int initialConsumers;

    @Value("${simulation.rider.deliveries-before-tired:5}")
    private int deliveriesBeforeTired;

    @Value("${simulation.rider.tired-rest-seconds:3}")
    private int tiredRestSeconds;

    @Value("${simulation.rider.accident-probability:0.05}")
    private double accidentProbability;

    @Value("${simulation.order.cancel-after-seconds:30}")
    private long cancelAfterSeconds;

    // ── Dependencies ───────────────────────────────────────────────────────
    private final WeatherController weather;
    private final SimpMessagingTemplate messagingTemplate;

    // ── Shared state ───────────────────────────────────────────────────────
    private OrderBuffer orderBuffer;
    private ExecutorService producerPool;
    private ExecutorService consumerPool;

    private final List<Rider> riders = new CopyOnWriteArrayList<>();
    private final AtomicLong totalOrders    = new AtomicLong(0);
    private final AtomicLong delivered      = new AtomicLong(0);
    private final AtomicLong cancelled      = new AtomicLong(0);
    private final Queue<Double> deliveryTimesHistory = new LinkedList<>();

    private volatile String status = "STOPPED"; // RUNNING | PAUSED | STOPPED

    // Background stale-order scanner
    private ScheduledExecutorService cancelScheduler;

    private int riderCounter = 0;

    public SimulationService(WeatherController weather,
                             SimpMessagingTemplate messagingTemplate) {
        this.weather = weather;
        this.messagingTemplate = messagingTemplate;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────

    public synchronized void start() {
        if ("RUNNING".equals(status)) return;

        log.info("[SIM] Starting simulation (producers={}, consumers={})",
                initialProducers, initialConsumers);

        // (Re)initialise buffer and counters on a fresh start
        if ("STOPPED".equals(status)) {
            orderBuffer = new OrderBuffer(bufferCapacity);
            totalOrders.set(0);
            delivered.set(0);
            cancelled.set(0);
            riders.clear();
            synchronized (deliveryTimesHistory) { deliveryTimesHistory.clear(); }
            riderCounter = 0;
        }

        producerPool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setName("producer-" + UUID.randomUUID().toString().substring(0, 4));
            t.setDaemon(true);
            return t;
        });
        consumerPool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setName("consumer-" + UUID.randomUUID().toString().substring(0, 4));
            t.setDaemon(true);
            return t;
        });

        // Spawn initial producers
        for (int i = 0; i < initialProducers; i++) {
            spawnProducer();
        }

        // Spawn initial riders/consumers
        for (int i = 0; i < initialConsumers; i++) {
            spawnRider();
        }

        // Stale-order cancellation scanner (every 5 seconds)
        cancelScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cancel-scanner");
            t.setDaemon(true);
            return t;
        });
        cancelScheduler.scheduleAtFixedRate(this::scanAndCancelStaleOrders, 5, 5, TimeUnit.SECONDS);

        status = "RUNNING";
        log.info("[SIM] Simulation RUNNING");
    }

    public synchronized void pause() {
        if (!"RUNNING".equals(status)) return;
        shutdownPools(false);
        status = "PAUSED";
        log.info("[SIM] Simulation PAUSED");
    }

    public synchronized void stop() {
        if ("STOPPED".equals(status)) return;
        shutdownPools(true);
        if (cancelScheduler != null) cancelScheduler.shutdownNow();
        orderBuffer.reset();
        status = "STOPPED";
        log.info("[SIM] Simulation STOPPED");
    }

    /** Add an extra rider consumer thread at runtime. */
    public synchronized void addRider() {
        if (!"RUNNING".equals(status)) return;
        spawnRider();
    }

    public synchronized void toggleWeather() {
        weather.toggle();
        log.info("[SIM] Weather toggled to {}", weather.getWeatherLabel());
    }

    public synchronized void setWeather(String w) {
        weather.setRainy("RAINY".equalsIgnoreCase(w));
    }

    // ─────────────────────────────────────────────────────────────────────
    // Thread spawning helpers
    // ─────────────────────────────────────────────────────────────────────

    private void spawnProducer() {
        String pid = "P-" + (char) ('A' + ThreadLocalRandom.current().nextInt(26))
                + ThreadLocalRandom.current().nextInt(100);
        ClientProducer producer = new ClientProducer(pid, orderBuffer, weather, totalOrders);
        producerPool.submit(producer);
        log.info("[SIM] Spawned producer {}", pid);
    }

    private void spawnRider() {
        riderCounter++;
        String riderId = "R" + String.format("%02d", riderCounter);
        String riderName = "Rider " + riderId;
        Rider rider = new Rider(riderId, riderName);
        riders.add(rider);

        RiderConsumer consumer = new RiderConsumer(
                rider, orderBuffer, weather,
                deliveriesBeforeTired, tiredRestSeconds, accidentProbability,
                delivered, cancelled, deliveryTimesHistory
        );
        consumerPool.submit(consumer);
        log.info("[SIM] Spawned rider {}", riderId);
    }

    private void shutdownPools(boolean immediate) {
        if (producerPool != null) {
            if (immediate) producerPool.shutdownNow();
            else producerPool.shutdown();
        }
        if (consumerPool != null) {
            if (immediate) consumerPool.shutdownNow();
            else consumerPool.shutdown();
        }
        try {
            if (producerPool != null) producerPool.awaitTermination(3, TimeUnit.SECONDS);
            if (consumerPool != null) consumerPool.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Stale-order scanner
    // ─────────────────────────────────────────────────────────────────────

    private void scanAndCancelStaleOrders() {
        if (orderBuffer == null) return;
        try {
            int count = orderBuffer.cancelStaleOrders(cancelAfterSeconds);
            if (count > 0) {
                cancelled.addAndGet(count);
                log.info("[SIM] Cancelled {} stale order(s)", count);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // WebSocket broadcast — fires every 500 ms via @Scheduled
    // ─────────────────────────────────────────────────────────────────────

    @Scheduled(fixedRateString = "${simulation.ws.broadcast-interval-ms:500}")
    public void broadcastState() {
        try {
            SimulationStateDTO dto = buildStateDTO();
            messagingTemplate.convertAndSend("/topic/simulation", dto);
        } catch (Exception e) {
            log.debug("[SIM] Broadcast error: {}", e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // DTO assembly
    // ─────────────────────────────────────────────────────────────────────

    public SimulationStateDTO buildStateDTO() {
        SimulationStateDTO dto = new SimulationStateDTO();
        dto.setSimulationStatus(status);
        dto.setWeather(weather.getWeatherLabel());
        dto.setRiders(new ArrayList<>(riders));

        if (orderBuffer != null) {
            dto.setEmptySlots(orderBuffer.getEmptySlotsCount());
            dto.setFilledSlots(orderBuffer.getFilledSlotsCount());
            dto.setMutexLocked(orderBuffer.isMutexLocked());
            dto.setBufferContents(orderBuffer.snapshot());
        } else {
            dto.setEmptySlots(bufferCapacity);
            dto.setFilledSlots(0);
            dto.setMutexLocked(false);
            dto.setBufferContents(Collections.emptyList());
        }

        long del = delivered.get();
        long can = cancelled.get();
        long tot = totalOrders.get();
        dto.setTotalOrders(tot);
        dto.setDelivered(del);
        dto.setCancelled(can);
        dto.setInQueue(orderBuffer != null ? orderBuffer.getFilledSlotsCount() : 0);

        synchronized (deliveryTimesHistory) {
            List<Double> history = new ArrayList<>(deliveryTimesHistory);
            dto.setDeliveryTimesHistory(history);
            double avg = history.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            dto.setAvgDeliveryTimeSeconds(avg);
        }

        return dto;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Stats endpoint helper
    // ─────────────────────────────────────────────────────────────────────

    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalOrders", totalOrders.get());
        stats.put("delivered", delivered.get());
        stats.put("cancelled", cancelled.get());
        double avg;
        synchronized (deliveryTimesHistory) {
            avg = deliveryTimesHistory.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        }
        stats.put("avgDeliveryTimeSeconds", Math.round(avg * 10.0) / 10.0);
        return stats;
    }

    public String getStatus() { return status; }
}
