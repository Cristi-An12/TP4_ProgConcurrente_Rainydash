package com.rainydash.model;

public class Rider {

    private String id;
    private String name;
    private RiderStatus status;
    private int deliveriesCompleted;
    private int deliveriesFailed;
    private Position currentPosition;
    private Position targetPosition;

    public Rider() {}

    public Rider(String id, String name) {
        this.id = id;
        this.name = name;
        this.status = RiderStatus.IDLE;
        this.deliveriesCompleted = 0;
        this.deliveriesFailed = 0;
        this.currentPosition = new Position(0, 0);
        this.targetPosition = new Position(0, 0);
    }

    // ── Inner class: Position ──────────────────────────────────────────────

    public static class Position {
        private double x;
        private double y;

        public Position() {}
        public Position(double x, double y) { this.x = x; this.y = y; }

        public double getX() { return x; }
        public void setX(double x) { this.x = x; }

        public double getY() { return y; }
        public void setY(double y) { this.y = y; }
    }

    // ── Getters & Setters ──────────────────────────────────────────────────

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public RiderStatus getStatus() { return status; }
    public void setStatus(RiderStatus status) { this.status = status; }

    public int getDeliveriesCompleted() { return deliveriesCompleted; }
    public void setDeliveriesCompleted(int deliveriesCompleted) { this.deliveriesCompleted = deliveriesCompleted; }

    public int getDeliveriesFailed() { return deliveriesFailed; }
    public void setDeliveriesFailed(int deliveriesFailed) { this.deliveriesFailed = deliveriesFailed; }

    public Position getCurrentPosition() { return currentPosition; }
    public void setCurrentPosition(Position currentPosition) { this.currentPosition = currentPosition; }

    public Position getTargetPosition() { return targetPosition; }
    public void setTargetPosition(Position targetPosition) { this.targetPosition = targetPosition; }
}
