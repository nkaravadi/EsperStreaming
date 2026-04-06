package com.example.esper.controller;

import com.example.esper.model.WindowConfig;
import com.example.esper.service.EventGenerator;
import com.example.esper.service.WindowManager;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collection;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class WindowController {

    private final WindowManager windowManager;
    private final EventGenerator eventGenerator;

    public WindowController(WindowManager windowManager, EventGenerator eventGenerator) {
        this.windowManager = windowManager;
        this.eventGenerator = eventGenerator;
    }

    @GetMapping("/windows")
    public Collection<WindowConfig> listWindows() {
        return windowManager.getAllWindows();
    }

    @GetMapping("/windows/{name}")
    public ResponseEntity<WindowConfig> getWindow(@PathVariable String name) {
        WindowConfig config = windowManager.getWindow(name);
        if (config == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(config);
    }

    @PostMapping("/generate/{name}")
    public ResponseEntity<Map<String, Object>> generateEvents(
            @PathVariable String name, @RequestBody Map<String, Integer> body) {
        int count = body.getOrDefault("count", 10);
        try {
            int generated = eventGenerator.generateEvents(name, count);
            return ResponseEntity.ok(Map.of("generated", generated, "window", name));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/generate/{name}/start")
    public ResponseEntity<Map<String, Object>> startGeneration(
            @PathVariable String name, @RequestBody Map<String, Integer> body) {
        int rate = body.getOrDefault("ratePerSecond", 5);
        try {
            eventGenerator.startContinuous(name, rate);
            return ResponseEntity.ok(Map.of("status", "started", "ratePerSecond", rate, "window", name));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/generate/{name}/stop")
    public ResponseEntity<Map<String, Object>> stopGeneration(@PathVariable String name) {
        eventGenerator.stopContinuous(name);
        return ResponseEntity.ok(Map.of("status", "stopped", "window", name));
    }

    @GetMapping("/generate/{name}/status")
    public ResponseEntity<Map<String, Object>> generationStatus(@PathVariable String name) {
        return ResponseEntity.ok(Map.of("running", eventGenerator.isRunning(name), "window", name));
    }
}
