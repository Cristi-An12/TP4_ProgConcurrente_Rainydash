package com.rainydash.model;

import java.time.LocalDateTime;

public class Order {

    private String id;
    private String clientName;
    private String item;          // "Pizza", "Sushi", "Burger", etc.
    private OrderPriority priority;
    private OrderStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime deliveredAt;
    private boolean delayed;
    private String assignedRiderId;

    public Order() {}

    public Order(String id, String clientName, String item, OrderPriority priority) {
        this.id = id;
        this.clientName = clientName;
        this.item = item;
        this.priority = priority;
        this.status = OrderStatus.QUEUED;
        this.createdAt = LocalDateTime.now();
        this.delayed = false;
    }

    // ── Getters & Setters ──────────────────────────────────────────────────

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getClientName() { return clientName; }
    public void setClientName(String clientName) { this.clientName = clientName; }

    public String getItem() { return item; }
    public void setItem(String item) { this.item = item; }

    public OrderPriority getPriority() { return priority; }
    public void setPriority(OrderPriority priority) { this.priority = priority; }

    public OrderStatus getStatus() { return status; }
    public void setStatus(OrderStatus status) { this.status = status; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getDeliveredAt() { return deliveredAt; }
    public void setDeliveredAt(LocalDateTime deliveredAt) { this.deliveredAt = deliveredAt; }

    public boolean isDelayed() { return delayed; }
    public void setDelayed(boolean delayed) { this.delayed = delayed; }

    public String getAssignedRiderId() { return assignedRiderId; }
    public void setAssignedRiderId(String assignedRiderId) { this.assignedRiderId = assignedRiderId; }
}
