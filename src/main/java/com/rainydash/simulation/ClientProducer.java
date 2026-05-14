package com.rainydash.simulation;

import com.rainydash.model.Order;
import com.rainydash.model.OrderPriority;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ClientProducer — Producer Thread
 *
 * Each instance represents a client who places delivery orders.
 * The thread continuously generates new orders and calls
 * {@link OrderBuffer#produce(Order)}, which internally uses semaphores
 * to block the thread when the buffer is full.
 *
 * Production rate is governed by the WeatherController: in RAINY weather
 * clients order more frequently (shorter inter-arrival time).
 */
public class ClientProducer implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(ClientProducer.class);

    private static final List<String> CLIENT_NAMES = List.of(
            "Ana", "Bruno", "Carla", "Diego", "Elena",
            "Fran", "Gabi", "Hugo", "Irene", "Juan"
    );
    private static final List<String> ITEMS = List.of(
            "🍕 Pizza", "🍣 Sushi", "🍔 Burger", "🌮 Taco",
            "🍜 Ramen", "🍗 Chicken", "🥗 Salad", "🍦 Ice Cream"
    );

    private final String producerId;
    private final OrderBuffer buffer;
    private final WeatherController weather;
    private final AtomicLong totalOrdersCounter;  // shared counter for stats

    // Base inter-arrival time between orders (ms)
    private static final int BASE_INTERVAL_MS = 2000;

    public ClientProducer(String producerId,
                          OrderBuffer buffer,
                          WeatherController weather,
                          AtomicLong totalOrdersCounter) {
        this.producerId = producerId;
        this.buffer = buffer;
        this.weather = weather;
        this.totalOrdersCounter = totalOrdersCounter;
    }

    @Override
    public void run() {
        log.info("[PRODUCER {}] started", producerId);
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        while (!Thread.currentThread().isInterrupted()) {
            try {
                // ── Build a new order ──────────────────────────────────
                String clientName = CLIENT_NAMES.get(rng.nextInt(CLIENT_NAMES.size()));
                String item       = ITEMS.get(rng.nextInt(ITEMS.size()));
                // 20% chance of PREMIUM order
                OrderPriority priority = rng.nextDouble() < 0.20
                        ? OrderPriority.PREMIUM
                        : OrderPriority.STANDARD;

                Order order = new Order(
                        UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                        clientName,
                        item,
                        priority
                );

                // ── PRODUCER CRITICAL PATH ─────────────────────────────
                // OrderBuffer.produce() performs:
                //   emptySlots.acquire()  → blocks here if buffer is full
                //   mutex.acquire()       → enter critical section
                //   insert into array
                //   mutex.release()       → leave critical section
                //   filledSlots.release() → wake up a waiting consumer
                buffer.produce(order);

                totalOrdersCounter.incrementAndGet();
                log.info("[PRODUCER {}] added order {} ({})", producerId, order.getId(), priority);

                // ── Wait before producing the next order ───────────────
                // In RAINY weather the multiplier doubles the rate (halves the sleep)
                double multiplier = weather.isRainy() ? weather.getProducerMultiplier() : 1.0;
                long sleepMs = (long) (BASE_INTERVAL_MS / multiplier);
                // Add ±30% jitter so arrivals are not perfectly uniform
                long jitter = (long) (sleepMs * 0.3 * (rng.nextDouble() - 0.5) * 2);
                Thread.sleep(Math.max(200, sleepMs + jitter));

            } catch (InterruptedException e) {
                // Thread was interrupted (simulation stopped/paused) — exit cleanly
                Thread.currentThread().interrupt();
                log.info("[PRODUCER {}] interrupted, stopping", producerId);
            }
        }
        log.info("[PRODUCER {}] exited", producerId);
    }
}
