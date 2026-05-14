# 🏍️ RainyDash — Delivery Simulator

> A Spring Boot simulation of the **Producer-Consumer problem** solved with Java semaphores.  
> Theme: a rainy-day food delivery service where bad weather spikes order demand.

---

## 1. Project Overview

RainyDash is an academic simulation that brings the **Producer-Consumer concurrency pattern** to life through the metaphor of a delivery platform.

- **Producers** = clients placing food orders (threads that insert into a shared buffer)
- **Consumers** = delivery riders picking up those orders (threads that remove from the buffer)
- **Shared buffer** = a fixed-capacity circular queue of pending orders
- **Semaphores** = the synchronisation mechanism that prevents overflow, starvation, and race conditions

The simulation is fully visual: a city map canvas shows riders moving in real time, a buffer panel displays the circular queue with live semaphore counters, and a stats panel tracks orders created / delivered / cancelled. Weather can be toggled between ☀️ SUNNY and 🌧️ RAINY — rainy weather doubles the order rate and introduces accidents.

---

## 2. Producer-Consumer Problem — Explanation

The Producer-Consumer (or Bounded Buffer) problem arises when one or more threads **produce** data items and one or more threads **consume** them, sharing a fixed-size buffer. Without coordination:

- **Overflow**: a producer writes to a full buffer, overwriting existing data.
- **Underflow**: a consumer reads from an empty buffer, getting garbage.
- **Race conditions**: two threads simultaneously modify the buffer's head/tail pointers, corrupting state.

**Semaphores** solve all three issues elegantly:

| Role | Class | Thread Type | Action |
|------|-------|-------------|--------|
| Producer | `ClientProducer` | One per active client | Places orders into buffer |
| Consumer | `RiderConsumer` | One per rider | Takes orders from buffer |
| Buffer | `OrderBuffer` | Shared resource | Bounded circular queue |
| Semaphore: `emptySlots` | — | — | Blocks producers when buffer is full |
| Semaphore: `filledSlots` | — | — | Blocks consumers when buffer is empty |
| Semaphore: `mutex` | — | — | Ensures mutual exclusion on buffer access |

---

## 3. Where Producer-Consumer Happens in the Code

### The Buffer
**`src/main/java/com/rainydash/simulation/OrderBuffer.java`**

This is the heart of the pattern. The three semaphores are declared here:

```java
// counts free slots  — initialised to CAPACITY
private final Semaphore emptySlots  = new Semaphore(capacity);
// counts filled slots — initialised to 0
private final Semaphore filledSlots = new Semaphore(0);
// binary mutex        — initialised to 1 (unlocked)
private final Semaphore mutex       = new Semaphore(1);
```

### Producer critical path — `OrderBuffer.produce()` (lines ~90–115)

```java
public void produce(Order order) throws InterruptedException {
    // ① Block if buffer is full (no free slots)
    emptySlots.acquire();

    // ② Enter critical section — only one thread may touch the array
    mutex.acquire();
    try {
        buffer[tail] = order;
        tail = (tail + 1) % capacity;
        count++;
    } finally {
        // ③ Leave critical section
        mutex.release();
    }

    // ④ Signal that one more order is available
    filledSlots.release();
}
```

### Consumer critical path — `OrderBuffer.consume()` (lines ~130–155)

```java
public Order consume() throws InterruptedException {
    // ① Block if buffer is empty (no filled slots)
    filledSlots.acquire();

    // ② Enter critical section
    mutex.acquire();
    Order order;
    try {
        order = extractWithPriority(); // PREMIUM orders go first
        count--;
    } finally {
        // ③ Leave critical section
        mutex.release();
    }

    // ④ Signal that one more slot is free
    emptySlots.release();

    return order;
}
```

### Producer thread — `ClientProducer.run()`
**`src/main/java/com/rainydash/simulation/ClientProducer.java`**

Generates an `Order` object, then delegates to `buffer.produce(order)`.  
Sleeps between orders; the sleep interval is halved in RAINY weather.

### Consumer thread — `RiderConsumer.run()`
**`src/main/java/com/rainydash/simulation/RiderConsumer.java`**

Calls `buffer.consume()`, simulates delivery travel time, handles TIRED rest and ACCIDENT events.

---

## 4. Architecture Diagram (ASCII)

```
Rainy Weather
     ↓ 2× rate
[ClientProducer×N]
       |
       | buffer.produce(order)
       ↓
  emptySlots.acquire()   ← BLOCKS if buffer full (emptySlots == 0)
       |
  mutex.acquire()        ← critical section start
       |
  ┌────────────────────────────────────────┐
  │           OrderBuffer                  │
  │  [ slot0 ][ slot1 ][ slot2 ] ... [N-1] │  ← circular array
  └────────────────────────────────────────┘
       |
  mutex.release()        ← critical section end
       |
  filledSlots.release()  ← wakes a waiting consumer
       |
       ↓
[RiderConsumer×M]
       |
  filledSlots.acquire()  ← BLOCKS if buffer empty (filledSlots == 0)
       |
  mutex.acquire()        ← critical section start
       |
  extract order (PREMIUM first)
       |
  mutex.release()        ← critical section end
       |
  emptySlots.release()   ← wakes a waiting producer
       |
  simulate delivery → DELIVERED / ACCIDENT → re-enqueue
```

---

## 5. How to Run

**Prerequisites:** Java 21, Maven 3.8+

```bash
# Clone / unzip the project, then:
cd rainydash
mvn spring-boot:run

# Open http://localhost:8080
```

Click **▶ Start** to begin. Click **🌧️ Rain** to spike order demand and watch `emptySlots` drop toward zero as producers fill the buffer faster than riders can consume.

---

## 6. Configuration

All parameters are in `src/main/resources/application.properties`:

```properties
# Size of the circular buffer (max queued orders)
simulation.buffer.capacity=10

# Threads spawned on start
simulation.producers.initial=3
simulation.consumers.initial=2

# RAINY weather doubles producer rate
simulation.rain.producer-multiplier=2.0

# Rider fatigue: rest after N consecutive deliveries
simulation.rider.deliveries-before-tired=5
simulation.rider.tired-rest-seconds=3

# Probability of accident per delivery in rain (0.0 – 1.0)
simulation.rider.accident-probability=0.05

# Orders older than this are auto-cancelled
simulation.order.cancel-after-seconds=30
```

---

## 7. Semaphore Implementation Detail

Below is the exact acquire/release sequence from `OrderBuffer.java`, annotated:

```java
// ═══════ PRODUCER (ClientProducer calls this) ═══════
public void produce(Order order) throws InterruptedException {

    emptySlots.acquire();   // (A) Decrement free-slot count.
                            //     If count was already 0, this call BLOCKS
                            //     the producer thread until a consumer calls
                            //     emptySlots.release() after removing an order.

    mutex.acquire();        // (B) Acquire the binary lock.
                            //     Guarantees only ONE thread modifies
                            //     buffer[], head, tail, count at a time.
    try {
        buffer[tail] = order;
        tail = (tail + 1) % capacity;
        count++;
    } finally {
        mutex.release();    // (C) Release the binary lock unconditionally
                            //     (finally ensures release even on exception).
    }

    filledSlots.release();  // (D) Increment filled-slot count.
                            //     If a consumer was blocked in filledSlots.acquire(),
                            //     it is woken up here and can now proceed.
}

// ═══════ CONSUMER (RiderConsumer calls this) ═══════
public Order consume() throws InterruptedException {

    filledSlots.acquire();  // (E) Decrement filled-slot count.
                            //     BLOCKS if buffer is empty (count == 0).

    mutex.acquire();        // (F) Acquire the binary lock.

    Order order;
    try {
        order = extractWithPriority(); // removes from head; PREMIUM first
        count--;
    } finally {
        mutex.release();    // (G) Release the binary lock.
    }

    emptySlots.release();   // (H) Increment free-slot count.
                            //     Wakes a blocked producer if any.

    return order;
}
```

### ⚠️ What happens if you remove the mutex?

Comment out lines **(B)**, **(C)**, **(F)**, **(G)** and run with high concurrency (`initialProducers=10`, `initialConsumers=5`):

- Two producers can both read `tail = 3`, both write to `buffer[3]`, and both increment `tail` to `4`. One order is **silently lost**.
- A producer and a consumer can simultaneously read the buffer, leading to a consumer extracting a `null` entry and a `NullPointerException` in delivery logic.
- `count` becomes inconsistent because `count++` and `count--` are not atomic Java operations.

The `emptySlots` / `filledSlots` semaphores prevent overflow/underflow, but **only the mutex prevents data corruption inside the critical section**.

---

## 8. Visual Guide

### Panel 1 — City Map
A 6×5 grid represents city blocks. Riders (coloured circles with their ID) move smoothly from the **BASE** (purple square, top-left) to delivery targets (blinking house icons). In rainy mode the canvas darkens and animated diagonal rain lines appear. Watch for 💥 ACCIDENT flashes — the rider turns red and the order is returned to the buffer.

### Panel 2 — Order Buffer
Ten slots arranged in a row represent the circular buffer. Colour codes show order age:

- **Green** = fresh (<10 s), **Yellow** = ageing (10–20 s), **Red** = stale (>20 s, about to be cancelled)
- **Gold border** = PREMIUM order (picked first by consumers)

**Key observation:** switch to 🌧️ RAINY and watch `emptySlots` count drop toward **0**. When it hits zero, the producer threads are **blocked** — you can verify this in the server logs: `emptySlots.acquire()` will not return until a rider consumes an order.

### Panel 3 — Stats & Controls
- **Delivery histogram** bars grow taller for slow deliveries (red) and shorter for fast ones (green).
- **Rider list** shows each rider's status in real time. An `ACCIDENT` rider blinks red for 8 seconds.
- Use **+ Rider** to add consumer threads mid-simulation and watch `emptySlots` recover.
