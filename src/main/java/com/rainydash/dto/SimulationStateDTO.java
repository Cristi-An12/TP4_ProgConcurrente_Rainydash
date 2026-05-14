package com.rainydash.dto;

import com.rainydash.model.Order;
import com.rainydash.model.Rider;

import java.util.List;

/**
 * Data Transfer Object sent over WebSocket every 500 ms.
 * Contains a full snapshot of the simulation state so the frontend
 * can render the canvas, buffer visualisation, and stats panel.
 */
public class SimulationStateDTO {

    private String simulationStatus;   // RUNNING | PAUSED | STOPPED
    private String weather;            // SUNNY | RAINY

    // Semaphore values (live)
    private int emptySlots;
    private int filledSlots;
    private boolean mutexLocked;

    // Buffer contents (ordered as they appear in the circular buffer)
    private List<Order> bufferContents;

    // Riders on the map
    private List<Rider> riders;

    // Aggregate statistics
    private long totalOrders;
    private long delivered;
    private long cancelled;
    private long inQueue;
    private double avgDeliveryTimeSeconds;

    // Last 20 delivery times for the histogram
    private List<Double> deliveryTimesHistory;

    public SimulationStateDTO() {}

    // ── Getters & Setters ──────────────────────────────────────────────────

    public String getSimulationStatus() { return simulationStatus; }
    public void setSimulationStatus(String simulationStatus) { this.simulationStatus = simulationStatus; }

    public String getWeather() { return weather; }
    public void setWeather(String weather) { this.weather = weather; }

    public int getEmptySlots() { return emptySlots; }
    public void setEmptySlots(int emptySlots) { this.emptySlots = emptySlots; }

    public int getFilledSlots() { return filledSlots; }
    public void setFilledSlots(int filledSlots) { this.filledSlots = filledSlots; }

    public boolean isMutexLocked() { return mutexLocked; }
    public void setMutexLocked(boolean mutexLocked) { this.mutexLocked = mutexLocked; }

    public List<Order> getBufferContents() { return bufferContents; }
    public void setBufferContents(List<Order> bufferContents) { this.bufferContents = bufferContents; }

    public List<Rider> getRiders() { return riders; }
    public void setRiders(List<Rider> riders) { this.riders = riders; }

    public long getTotalOrders() { return totalOrders; }
    public void setTotalOrders(long totalOrders) { this.totalOrders = totalOrders; }

    public long getDelivered() { return delivered; }
    public void setDelivered(long delivered) { this.delivered = delivered; }

    public long getCancelled() { return cancelled; }
    public void setCancelled(long cancelled) { this.cancelled = cancelled; }

    public long getInQueue() { return inQueue; }
    public void setInQueue(long inQueue) { this.inQueue = inQueue; }

    public double getAvgDeliveryTimeSeconds() { return avgDeliveryTimeSeconds; }
    public void setAvgDeliveryTimeSeconds(double avgDeliveryTimeSeconds) { this.avgDeliveryTimeSeconds = avgDeliveryTimeSeconds; }

    public List<Double> getDeliveryTimesHistory() { return deliveryTimesHistory; }
    public void setDeliveryTimesHistory(List<Double> deliveryTimesHistory) { this.deliveryTimesHistory = deliveryTimesHistory; }
}
