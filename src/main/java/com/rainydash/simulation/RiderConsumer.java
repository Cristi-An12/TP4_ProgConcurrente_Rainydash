package com.rainydash.simulation;

import com.rainydash.model.Order;
import com.rainydash.model.OrderStatus;
import com.rainydash.model.Rider;
import com.rainydash.model.RiderStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * RiderConsumer — Consumer Thread
 *
 * Each instance represents a delivery rider that continuously picks orders
 * from the {@link OrderBuffer} and simulates a delivery.
 *
 * The thread calls {@link OrderBuffer#consume()}, which internally uses
 * semaphores to block the thread when the buffer is empty.
 *
 * Special states:
 *  - TIRED:    after N consecutive deliveries, the rider rests for a few seconds
 *  - ACCIDENT: in rainy weather, 5% chance the rider has an accident;
 *              the order is returned to the buffer and the rider is unavailable
 *              for 8 seconds
 */
public class RiderConsumer implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(RiderConsumer.class);

    // Simulation config
    private static final int TIRED_ACCIDENT_DURATION_MS = 8_000;
    private static final int BASE_DELIVERY_MS            = 3_000;
    private static final double RAIN_DELIVERY_MULTIPLIER = 1.4; // +40% time in rain

    private final Rider rider;
    private final OrderBuffer buffer;
    private final WeatherController weather;
    private final int deliveriesBeforeTired;
    private final int tiredRestMs;
    private final double accidentProbability;
    private final AtomicLong deliveredCounter;
    private final AtomicLong cancelledCounter;
    private final Queue<Double> deliveryTimesHistory; // shared, last 20 entries

    public RiderConsumer(Rider rider,
                         OrderBuffer buffer,
                         WeatherController weather,
                         int deliveriesBeforeTired,
                         int tiredRestSeconds,
                         double accidentProbability,
                         AtomicLong deliveredCounter,
                         AtomicLong cancelledCounter,
                         Queue<Double> deliveryTimesHistory) {
        this.rider = rider;
        this.buffer = buffer;
        this.weather = weather;
        this.deliveriesBeforeTired = deliveriesBeforeTired;
        this.tiredRestMs = tiredRestSeconds * 1000;
        this.accidentProbability = accidentProbability;
        this.deliveredCounter = deliveredCounter;
        this.cancelledCounter = cancelledCounter;
        this.deliveryTimesHistory = deliveryTimesHistory;
    }

    @Override
    public void run() {
        log.info("[RIDER {}] {} started", rider.getId(), rider.getName());
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int consecutiveDeliveries = 0;

        while (!Thread.currentThread().isInterrupted()) {
            try {
                // Check if already tired and needs rest before attempting next delivery
                if (consecutiveDeliveries >= deliveriesBeforeTired) {
                    rider.setStatus(RiderStatus.TIRED);
                    log.info("[RIDER {}] is TIRED — resting {}ms", rider.getId(), tiredRestMs);
                    Thread.sleep(tiredRestMs);
                    consecutiveDeliveries = 0;
                }

                rider.setStatus(RiderStatus.IDLE);

                // ── CONSUMER CRITICAL PATH ─────────────────────────────
                // OrderBuffer.consume() performs:
                //   filledSlots.acquire()  → blocks here if buffer is empty
                //   mutex.acquire()        → enter critical section
                //   extract from array
                //   mutex.release()        → leave critical section
                //   emptySlots.release()   → wake up a waiting producer
                Order order = buffer.consume();

                if (order == null || order.getStatus() == OrderStatus.CANCELLED) {
                    // Order was cancelled while in buffer — skip silently
                    continue;
                }

                order.setStatus(OrderStatus.IN_DELIVERY);
                order.setAssignedRiderId(rider.getId());
                rider.setStatus(RiderStatus.DELIVERING);

                // Randomly set a target position on the 5×5 grid
                rider.setTargetPosition(new Rider.Position(
                        rng.nextInt(5),
                        rng.nextInt(5)
                ));

                log.info("[RIDER {}] delivering order {} ({})",
                        rider.getId(), order.getId(), order.getPriority());

                // ── Simulate delivery time ─────────────────────────────
                long deliveryMs = (long) (BASE_DELIVERY_MS + rng.nextInt(2000));
                if (weather.isRainy()) {
                    deliveryMs = (long) (deliveryMs * RAIN_DELIVERY_MULTIPLIER);
                    order.setDelayed(true);
                }

                // Interpolate rider position toward target during delivery
                moveRider(deliveryMs);

                // ── Accident check (rain only) ─────────────────────────
                if (weather.isRainy() && rng.nextDouble() < accidentProbability) {
                    log.warn("[RIDER {}] had an ACCIDENT! Order {} returned to buffer",
                            rider.getId(), order.getId());
                    rider.setStatus(RiderStatus.ACCIDENT);
                    rider.setDeliveriesFailed(rider.getDeliveriesFailed() + 1);

                    // Return order to buffer (re-enqueue)
                    order.setStatus(OrderStatus.QUEUED);
                    order.setAssignedRiderId(null);
                    buffer.produce(order);

                    // Rider is unavailable for 8 seconds
                    Thread.sleep(TIRED_ACCIDENT_DURATION_MS);
                    rider.setCurrentPosition(new Rider.Position(0, 0));
                    consecutiveDeliveries = 0;
                    continue;
                }

                // ── Successful delivery ────────────────────────────────
                order.setStatus(OrderStatus.DELIVERED);
                order.setDeliveredAt(LocalDateTime.now());

                double elapsedSec = deliveryMs / 1000.0;
                rider.setDeliveriesCompleted(rider.getDeliveriesCompleted() + 1);
                deliveredCounter.incrementAndGet();
                consecutiveDeliveries++;

                // Record delivery time for histogram (keep last 20)
                synchronized (deliveryTimesHistory) {
                    deliveryTimesHistory.add(elapsedSec);
                    while (deliveryTimesHistory.size() > 20) deliveryTimesHistory.poll();
                }

                // Rider returns to base
                rider.setCurrentPosition(new Rider.Position(0, 0));
                log.info("[RIDER {}] delivered order {} in {:.1f}s",
                        rider.getId(), order.getId(), elapsedSec);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("[RIDER {}] interrupted, stopping", rider.getId());
            }
        }
        rider.setStatus(RiderStatus.IDLE);
        log.info("[RIDER {}] exited", rider.getId());
    }

    /**
     * Gradually moves the rider from current position to target position
     * over the given duration. The frontend uses currentPosition for smooth
     * canvas animation; we update it in steps here so each WS broadcast
     * reflects incremental progress.
     */
    private void moveRider(long totalMs) throws InterruptedException {
        final int STEPS = 20;
        long stepMs = totalMs / STEPS;
        Rider.Position start = rider.getCurrentPosition();
        Rider.Position target = rider.getTargetPosition();

        for (int step = 1; step <= STEPS; step++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            double t = (double) step / STEPS;
            rider.setCurrentPosition(new Rider.Position(
                    start.getX() + (target.getX() - start.getX()) * t,
                    start.getY() + (target.getY() - start.getY()) * t
            ));
            Thread.sleep(stepMs);
        }
    }
}
