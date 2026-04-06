package com.example.esper.service;

import com.example.esper.model.WindowConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class WindowManager {
    private static final Logger log = LoggerFactory.getLogger(WindowManager.class);

    private final Map<String, WindowConfig> windows = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final EsperService esperService;

    @Value("${esper.window.config.dir:config/windows}")
    private String configDir;

    public WindowManager(EsperService esperService) {
        this.esperService = esperService;
    }

    @PostConstruct
    public void init() {
        File dir = new File(configDir);
        if (!dir.exists() || !dir.isDirectory()) {
            log.warn("Window config directory not found: {}", configDir);
            return;
        }
        File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
        if (files == null) return;

        for (File file : files) {
            try {
                WindowConfig config = objectMapper.readValue(file, WindowConfig.class);
                esperService.createWindow(config);
                windows.put(config.getName(), config);
                log.info("Created window: {}", config.getName());
            } catch (Exception e) {
                log.error("Failed to load window config: {}", file.getName(), e);
            }
        }
    }

    public Collection<WindowConfig> getAllWindows() {
        return windows.values();
    }

    public WindowConfig getWindow(String name) {
        return windows.get(name);
    }
}
