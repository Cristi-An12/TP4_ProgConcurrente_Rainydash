package com.rainydash.simulation;

import com.rainydash.model.Order;
import com.rainydash.model.OrderPriority;
import com.rainydash.model.OrderStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * OrderBuffer — Shared Bounded Buffer (Producer-Consumer Pattern)
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * This class is the heart of the simulation. It implements the classic
 * Producer-Consumer problem using THREE semaphores:
 *
 *   1. emptySlots  — counts how many slots are FREE in the buffer.
 *                    Producers ACQUIRE this before inserting.
 *                    Consumers RELEASE this after removing.
 *                    → If buffer is FULL, producers BLOCK here automatically.
 *
 *   2. filledSlots — counts how many slots are OCCUPIED in the buffer.
 *                    Consumers ACQUIRE this before removing.
 *                    Producers RELEASE this after inserting.
 *                    → If buffer is EMPTY, consumers BLOCK here automatically.
 *
 *   3. mutex       — binary semaphore (initialised to 1) used as a lock.
 *                    Both producers and consumers ACQUIRE this before touching
 *                    the buffer array, then RELEASE it immediately after.
 *                    → Without mutex, concurrent array access would corrupt state.
 *
 * Semaphore sequence for PRODUCERS:
 *   emptySlots.acquire()   // wait until there is at least one free slot
 *   mutex.acquire()        // enter critical section
 *   ... insert order ...
 *   mutex.release()        // leave critical section
 *   filledSlots.release()  // signal that one more order is available
 *
 * Semaphore sequence for CONSUMERS:
 *   filledSlots.acquire()  // wait until there is at least one order
 *   mutex.acquire()        // enter critical section
 *   ... remove order ...
 *   mutex.release()        // leave critical section
 *   emptySlots.release()   // signal that one more slot is free
 *
 * ⚠  DEMONSTRATION: comment out mutex.acquire() / mutex.release() and run
 *    with high concurrency — you will observe corrupted insertions/removals
 *    (lost orders, wrong head/tail pointers). The mutex prevents this.
 * ═══════════════════════════════════════════════════════════════════════════
 */
public class OrderBuffer {

    private static final Logger log = LoggerFactory.getLogger(OrderBuffer.class);

    // ── Fixed-capacity circular array ─────────────────────────────────────
    private final Order[] buffer;
    private final int capacity;
    private int head = 0;   // next read position
    private int tail = 0;   // next write position
    private int count = 0;  // current number of elements

    // ── The three semaphores ───────────────────────────────────────────────
    private final Semaphore emptySlots;   // counts free slots
    private final Semaphore filledSlots;  // counts occupied slots
    private final Semaphore mutex;        // binary mutex for mutual exclusion

    public OrderBuffer(int capacity) {
        this.capacity = capacity;
        this.buffer = new Order[capacity];

        // emptySlots starts at CAPACITY (all slots free)
        this.emptySlots = new Semaphore(capacity);
        // filledSlots starts at 0 (nothing to consume yet)
        this.filledSlots = new Semaphore(0);
        // mutex is binary: initialised to 1 (unlocked)
        this.mutex = new Semaphore(1);
    }

    // ─────────────────────────────────────────────────────────────────────
    // PRODUCER SIDE — called from ClientProducer.run()
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Insert an order into the buffer.
     * Blocks if the buffer is full (emptySlots == 0).
     *
     * @param order the order to enqueue
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public void produce(Order order) throws InterruptedException {
        // ① Wait for a free slot — BLOCKS if buffer is full
        //    This is what stops producers from overflowing the buffer.
        emptySlots.acquire();

        // ② Enter critical section — only one thread at a time may touch the array
        //    Without this, two producers could write to the same tail position.
        mutex.acquire();
        try {
            // For PREMIUM orders, try to place them at the head of the queue
            // by marking priority so the consumer picks them first. In a simple
            // circular buffer we record them in arrival order but RiderConsumer
            // will prefer PREMIUM via a peek-and-swap approach.
            buffer[tail] = order;
            tail = (tail + 1) % capacity;
            count++;
            log.debug("[BUFFER] Produced '{}' | count={} tail={}", order.getId(), count, tail);
        } finally {
            // ③ Leave critical section
            mutex.release();
        }

        // ④ Signal that one more order is available for consumers
        filledSlots.release();
    }

    // ─────────────────────────────────────────────────────────────────────
    // CONSUMER SIDE — called from RiderConsumer.run()
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Remove and return the next order from the buffer.
     * Blocks if the buffer is empty (filledSlots == 0).
     *
     * @return the next order to deliver
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public Order consume() throws InterruptedException {
        // ① Wait for an available order — BLOCKS if buffer is empty
        //    This is what prevents riders from polling on an empty queue.
        filledSlots.acquire();

        // ② Enter critical section
        mutex.acquire();
        Order order;
        try {
            // Prefer PREMIUM orders: scan the occupied slots and swap a PREMIUM
            // order to the head position before extracting.
            order = extractWithPriority();
            count--;
            log.debug("[BUFFER] Consumed '{}' | count={} head={}", order.getId(), count, head);
        } finally {
            // ③ Leave critical section
            mutex.release();
        }

        // ④ Signal that one more slot is now free for producers
        emptySlots.release();

        return order;
    }

    /**
     * Extract the highest-priority order from the buffer.
     * If a PREMIUM order is found ahead of the head, swap it to the front.
     * Must be called while holding the mutex.
     */
    private Order extractWithPriority() {
        // Scan occupied slots for a PREMIUM order
        for (int i = 0; i < count; i++) {
            int idx = (head + i) % capacity;
            if (buffer[idx] != null && buffer[idx].getPriority() == OrderPriority.PREMIUM) {
                // Swap this PREMIUM order to the head position
                Order premiumOrder = buffer[idx];
                buffer[idx] = buffer[head];
                buffer[head] = premiumOrder;
                break;
            }
        }
        Order order = buffer[head];
        buffer[head] = null;
        head = (head + 1) % capacity;
        return order;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Cancellation scanner — called by background thread in SimulationService
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Scan all occupied slots and cancel orders older than {@code maxAgeSeconds}.
     * This method acquires the mutex to safely iterate the buffer array.
     *
     * @param maxAgeSeconds age threshold in seconds
     * @return number of orders cancelled
     */
    public int cancelStaleOrders(long maxAgeSeconds) throws InterruptedException {
        mutex.acquire();
        int cancelled = 0;
        try {
            LocalDateTime now = LocalDateTime.now();
            for (int i = 0; i < capacity; i++) {
                Order o = buffer[i];
                if (o != null && o.getStatus() == com.rainydash.model.OrderStatus.QUEUED) {
                    long age = ChronoUnit.SECONDS.between(o.getCreatedAt(), now);
                    if (age >= maxAgeSeconds) {
                        o.setStatus(OrderStatus.CANCELLED);
                        buffer[i] = null;
                        // Adjust pointers carefully for a circular buffer
                        count--;
                        // Signal that one slot is now free (one consumer slot freed)
                        // We also need to "take back" the filledSlots permit we never consumed
                        // by acquiring it without a corresponding produce.
                        filledSlots.acquire();
                        emptySlots.release();
                        cancelled++;
                        log.info("[BUFFER] Cancelled stale order '{}'", o.getId());
                    }
                }
            }
        } finally {
            mutex.release();
        }
        return cancelled;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Introspection — used by SimulationService to build state snapshots
    // ─────────────────────────────────────────────────────────────────────

    /** Live count of available free slots (semaphore permit count). */
    public int getEmptySlotsCount() { return emptySlots.availablePermits(); }

    /** Live count of orders waiting to be consumed (semaphore permit count). */
    public int getFilledSlotsCount() { return filledSlots.availablePermits(); }

    /** True if the mutex is currently held by a thread (0 permits available). */
    public boolean isMutexLocked() { return mutex.availablePermits() == 0; }

    /** Total buffer capacity. */
    public int getCapacity() { return capacity; }

    /**
     * Return a snapshot list of current buffer contents for UI rendering.
     * Acquires mutex to prevent inconsistent reads.
     */
    public List<Order> snapshot() {
        List<Order> result = new ArrayList<>(capacity);
        try {
            mutex.acquire();
            try {
                for (int i = 0; i < capacity; i++) {
                    result.add(buffer[i]); // null means empty slot
                }
            } finally {
                mutex.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return result;
    }

    /** Reset the buffer to empty state (called on simulation stop). */
    public void reset() {
        try {
            mutex.acquire();
            try {
                for (int i = 0; i < capacity; i++) buffer[i] = null;
                head = 0; tail = 0; count = 0;
                // Drain and re-initialise semaphores
                emptySlots.drainPermits();
                filledSlots.drainPermits();
                emptySlots.release(capacity);
            } finally {
                mutex.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
