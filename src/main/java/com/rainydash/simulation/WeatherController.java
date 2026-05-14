package com.rainydash.simulation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * WeatherController — manages the SUNNY/RAINY toggle.
 *
 * Accessed by both producers (to adjust arrival rate) and
 * consumers (to calculate delivery time and accident chance).
 * Uses AtomicBoolean for thread-safe toggling without a lock.
 */
@Component
public class WeatherController {

    private final AtomicBoolean rainy = new AtomicBoolean(false);

    @Value("${simulation.rain.producer-multiplier:2.0}")
    private double producerMultiplier;

    public boolean isRainy() {
        return rainy.get();
    }

    public String getWeatherLabel() {
        return rainy.get() ? "RAINY" : "SUNNY";
    }

    public void setRainy(boolean value) {
        rainy.set(value);
    }

    public void toggle() {
        rainy.set(!rainy.get());
    }

    public double getProducerMultiplier() {
        return producerMultiplier;
    }
}
