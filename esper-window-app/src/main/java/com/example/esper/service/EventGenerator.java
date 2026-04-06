package com.example.esper.service;

import com.example.esper.model.ColumnDef;
import com.example.esper.model.WindowConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;

@Service
public class EventGenerator {
    private static final Logger log = LoggerFactory.getLogger(EventGenerator.class);

    private static final String[] SYMBOLS = {"AAPL", "GOOGL", "MSFT", "AMZN", "TSLA", "META", "NVDA", "JPM", "BAC", "WMT"};
    private static final String[] SIDES = {"BUY", "SELL"};
    private static final String[] STATUSES = {"NEW", "PARTIAL", "FILLED", "CANCELLED"};
    private static final String[] EXCHANGES = {"NYSE", "NASDAQ", "BATS", "ARCA", "IEX"};
    private static final Random RANDOM = new Random();

    private final EsperService esperService;
    private final WindowManager windowManager;
    private final Map<String, ScheduledFuture<?>> runningGenerators = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4, r -> {
        Thread t = new Thread(r, "event-gen");
        t.setDaemon(true);
        return t;
    });

    // Track generated keys per window for updates/deletes
    private final Map<String, List<Map<String, Object>>> generatedKeys = new ConcurrentHashMap<>();

    public EventGenerator(EsperService esperService, WindowManager windowManager) {
        this.esperService = esperService;
        this.windowManager = windowManager;
    }

    public int generateEvents(String windowName, int count) {
        WindowConfig config = windowManager.getWindow(windowName);
        if (config == null) throw new IllegalArgumentException("Unknown window: " + windowName);

        for (int i = 0; i < count; i++) {
            generateAndSendEvent(config);
        }
        log.info("Generated {} events for window {}", count, windowName);
        return count;
    }

    public void startContinuous(String windowName, int ratePerSecond) {
        WindowConfig config = windowManager.getWindow(windowName);
        if (config == null) throw new IllegalArgumentException("Unknown window: " + windowName);

        stopContinuous(windowName);

        long periodMs = Math.max(1, 1000 / ratePerSecond);
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> {
            try {
                generateAndSendEvent(config);
            } catch (Exception e) {
                log.error("Error generating event for {}", windowName, e);
            }
        }, 0, periodMs, TimeUnit.MILLISECONDS);

        runningGenerators.put(windowName, future);
        log.info("Started continuous generation for {} at {} events/sec", windowName, ratePerSecond);
    }

    public void stopContinuous(String windowName) {
        ScheduledFuture<?> future = runningGenerators.remove(windowName);
        if (future != null) {
            future.cancel(false);
            log.info("Stopped continuous generation for {}", windowName);
        }
    }

    public boolean isRunning(String windowName) {
        return runningGenerators.containsKey(windowName);
    }

    private void generateAndSendEvent(WindowConfig config) {
        String name = config.getName();

        // Decide action: 70% upsert (insert/update), 20% update existing, 10% delete
        List<Map<String, Object>> keys = generatedKeys.computeIfAbsent(name, k -> new CopyOnWriteArrayList<>());
        double action = RANDOM.nextDouble();

        if (action < 0.1 && !keys.isEmpty()) {
            // Delete a random existing key
            int idx = RANDOM.nextInt(keys.size());
            Map<String, Object> pkValues = keys.remove(idx);
            esperService.sendEvent(name + "DeleteEvent", pkValues);
        } else if (action < 0.3 && !keys.isEmpty()) {
            // Update an existing row
            int idx = RANDOM.nextInt(keys.size());
            Map<String, Object> existingPk = keys.get(idx);
            Map<String, Object> event = generateRandomRow(config);
            // Override with existing PK values
            for (String pk : config.getPrimaryKeys()) {
                event.put(pk, existingPk.get(pk));
            }
            esperService.sendEvent(name + "UpsertEvent", event);
        } else {
            // Insert new
            Map<String, Object> event = generateRandomRow(config);
            Map<String, Object> pkValues = new LinkedHashMap<>();
            for (String pk : config.getPrimaryKeys()) {
                pkValues.put(pk, event.get(pk));
            }
            keys.add(pkValues);
            esperService.sendEvent(name + "Event", event);
        }
    }

    private Map<String, Object> generateRandomRow(WindowConfig config) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (ColumnDef col : config.getColumns()) {
            row.put(col.getName(), generateValue(col));
        }
        return row;
    }

    private Object generateValue(ColumnDef col) {
        String name = col.getName().toLowerCase();
        return switch (col.getType().toLowerCase()) {
            case "string" -> generateStringValue(name);
            case "int", "integer" -> generateIntValue(name);
            case "long" -> name.contains("timestamp") ? System.currentTimeMillis() : RANDOM.nextLong(1, 1000000);
            case "double" -> generateDoubleValue(name);
            case "float" -> (float) (RANDOM.nextDouble() * 1000);
            case "boolean" -> RANDOM.nextBoolean();
            default -> "unknown";
        };
    }

    private String generateStringValue(String name) {
        if (name.contains("symbol")) return SYMBOLS[RANDOM.nextInt(SYMBOLS.length)];
        if (name.contains("side")) return SIDES[RANDOM.nextInt(SIDES.length)];
        if (name.contains("status")) return STATUSES[RANDOM.nextInt(STATUSES.length)];
        if (name.contains("exchange")) return EXCHANGES[RANDOM.nextInt(EXCHANGES.length)];
        if (name.contains("id") || name.contains("order")) return UUID.randomUUID().toString().substring(0, 8);
        return "val-" + RANDOM.nextInt(10000);
    }

    private int generateIntValue(String name) {
        if (name.contains("size") || name.contains("quantity") || name.contains("qty")) {
            return RANDOM.nextInt(1, 10000);
        }
        return RANDOM.nextInt(1, 1000);
    }

    private double generateDoubleValue(String name) {
        if (name.contains("price") || name.contains("bid") || name.contains("ask")) {
            return Math.round(RANDOM.nextDouble() * 50000) / 100.0; // 0.01 to 500.00
        }
        return Math.round(RANDOM.nextDouble() * 100000) / 100.0;
    }
}
