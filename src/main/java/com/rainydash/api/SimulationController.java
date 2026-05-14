package com.rainydash.api;

import com.rainydash.dto.SimulationStateDTO;
import com.rainydash.simulation.SimulationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * SimulationController — REST API endpoints for controlling the simulation.
 */
@RestController
@RequestMapping("/api/simulation")
@CrossOrigin
public class SimulationController {

    private final SimulationService simulationService;

    public SimulationController(SimulationService simulationService) {
        this.simulationService = simulationService;
    }

    /** Full state snapshot (same payload as WebSocket broadcast). */
    @GetMapping("/status")
    public ResponseEntity<SimulationStateDTO> getStatus() {
        return ResponseEntity.ok(simulationService.buildStateDTO());
    }

    /** Start (or resume) the simulation. */
    @PostMapping("/start")
    public ResponseEntity<Map<String, String>> start() {
        simulationService.start();
        return ok("started");
    }

    /** Pause all threads. */
    @PostMapping("/pause")
    public ResponseEntity<Map<String, String>> pause() {
        simulationService.pause();
        return ok("paused");
    }

    /** Stop and reset the simulation. */
    @PostMapping("/stop")
    public ResponseEntity<Map<String, String>> stop() {
        simulationService.stop();
        return ok("stopped");
    }

    /**
     * Toggle or explicitly set weather.
     * Body: { "weather": "RAINY" | "SUNNY" }  (optional — toggles if absent)
     */
    @PostMapping("/weather")
    public ResponseEntity<Map<String, String>> weather(
            @RequestBody(required = false) Map<String, String> body) {
        if (body != null && body.containsKey("weather")) {
            simulationService.setWeather(body.get("weather"));
        } else {
            simulationService.toggleWeather();
        }
        return ok("weather updated");
    }

    /** Spawn an additional rider consumer thread. */
    @PostMapping("/add-rider")
    public ResponseEntity<Map<String, String>> addRider() {
        simulationService.addRider();
        return ok("rider added");
    }

    /** Aggregate statistics. */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> stats() {
        return ResponseEntity.ok(simulationService.getStats());
    }

    // ── Utility ────────────────────────────────────────────────────────────

    private ResponseEntity<Map<String, String>> ok(String message) {
        return ResponseEntity.ok(Map.of("status", message));
    }
}
