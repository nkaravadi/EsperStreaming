package com.example.esper.service;

import com.example.esper.model.DataMessage;
import com.espertech.esper.common.client.EventBean;
import com.espertech.esper.runtime.client.EPStatement;
import com.espertech.esper.runtime.client.UpdateListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class SubscriptionManager {
    private static final Logger log = LoggerFactory.getLogger(SubscriptionManager.class);

    private final EsperService esperService;
    private final SimpMessagingTemplate messagingTemplate;
    private final Map<String, Subscription> activeSubscriptions = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "subscription-worker");
        t.setDaemon(true);
        return t;
    });

    public SubscriptionManager(EsperService esperService, SimpMessagingTemplate messagingTemplate) {
        this.esperService = esperService;
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Subscribe a user session to a window with an optional WHERE clause.
     *
     * Critical design for correctness:
     * 1. Acquire the window lock.
     * 2. Attach the update listener (buffering events into a queue).
     * 3. Begin safe iteration (snapshot) of the window.
     * 4. Release the lock.
     * 5. Stream snapshot rows to the client.
     * 6. Send snapshot_complete marker.
     * 7. Drain buffered events and send them.
     * 8. Switch to live mode (listener sends directly).
     *
     * This ensures no events are missed and ordering is preserved.
     */
    public String subscribe(String sessionId, String windowName, String whereClause) {
        String subscriptionId = UUID.randomUUID().toString();
        String destination = "/queue/data";

        AtomicLong seqCounter = new AtomicLong(0);
        BlockingQueue<DataMessage> buffer = new LinkedBlockingQueue<>();
        Subscription subscription = new Subscription(subscriptionId, sessionId, windowName, whereClause);

        activeSubscriptions.put(subscriptionId, subscription);

        executor.submit(() -> {
            try {
                performSubscription(subscription, seqCounter, buffer, destination);
            } catch (Exception e) {
                log.error("Subscription failed: {}", subscriptionId, e);
                sendError(sessionId, subscriptionId, destination, e.getMessage());
            }
        });

        return subscriptionId;
    }

    private void performSubscription(Subscription subscription, AtomicLong seqCounter,
                                     BlockingQueue<DataMessage> buffer, String destination) {
        String windowName = subscription.windowName;
        String whereClause = subscription.whereClause;
        String subscriptionId = subscription.subscriptionId;
        String sessionId = subscription.sessionId;

        ReentrantLock lock = esperService.getWindowLock(windowName);
        EPStatement windowStmt = esperService.getWindowStatement(windowName);

        if (lock == null || windowStmt == null) {
            sendError(sessionId, subscriptionId, destination, "Window not found: " + windowName);
            return;
        }

        // Create a filtered listener statement if there's a WHERE clause,
        // otherwise listen on the named window statement directly.
        EPStatement listenerStmt;
        boolean ownsListenerStmt;
        if (whereClause != null && !whereClause.isBlank()) {
            listenerStmt = esperService.createFilteredStatement(windowName, whereClause);
            ownsListenerStmt = true;
        } else {
            listenerStmt = windowStmt;
            ownsListenerStmt = false;
        }

        // Phase 1: Acquire lock, attach listener, snapshot, release lock
        //
        // Named window listener semantics:
        //   newEvents only        -> INSERT
        //   newEvents + oldEvents -> UPDATE (new has current values, old has previous)
        //   oldEvents only        -> DELETE
        UpdateListener listener = (newEvents, oldEvents, stmt, rt) -> {
            if (!subscription.active) return;

            boolean isUpdate = newEvents != null && oldEvents != null;

            if (isUpdate) {
                // UPDATE: send the new values with type "update"
                for (EventBean bean : newEvents) {
                    Map<String, Object> data = esperService.eventBeanToMap(bean);
                    DataMessage msg = new DataMessage(
                            seqCounter.incrementAndGet(), "update", windowName, subscriptionId, data);
                    sendOrBuffer(subscription, sessionId, destination, buffer, msg);
                }
            } else {
                if (newEvents != null) {
                    // INSERT
                    for (EventBean bean : newEvents) {
                        Map<String, Object> data = esperService.eventBeanToMap(bean);
                        DataMessage msg = new DataMessage(
                                seqCounter.incrementAndGet(), "insert", windowName, subscriptionId, data);
                        sendOrBuffer(subscription, sessionId, destination, buffer, msg);
                    }
                }
                if (oldEvents != null) {
                    // DELETE
                    for (EventBean bean : oldEvents) {
                        Map<String, Object> data = esperService.eventBeanToMap(bean);
                        DataMessage msg = new DataMessage(
                                seqCounter.incrementAndGet(), "delete", windowName, subscriptionId, data);
                        sendOrBuffer(subscription, sessionId, destination, buffer, msg);
                    }
                }
            }
        };

        lock.lock();
        try {
            listenerStmt.addListener(listener);
            subscription.listener = listener;
            subscription.listenerStmt = listenerStmt;
            subscription.ownsListenerStmt = ownsListenerStmt;

            // Snapshot: iterate the window (use safeIterator for thread safety)
            // For filtered queries, we iterate window and apply filter manually,
            // or better, use fire-and-forget style query.
            // We iterate under the lock to guarantee consistency with the listener.
            Iterator<EventBean> iterator = windowStmt.safeIterator();
            List<Map<String, Object>> snapshotRows = new ArrayList<>();
            try {
                while (iterator.hasNext()) {
                    snapshotRows.add(esperService.eventBeanToMap(iterator.next()));
                }
            } finally {
                if (iterator instanceof AutoCloseable ac) {
                    try { ac.close(); } catch (Exception ignored) {}
                }
            }

            // If there's a WHERE clause, use a FAF query under the lock for a
            // consistent filtered snapshot. This guarantees the snapshot and listener
            // see the same point-in-time state.
            if (whereClause != null && !whereClause.isBlank()) {
                try {
                    snapshotRows = esperService.executeQuery(windowName, whereClause);
                } catch (Exception e) {
                    log.warn("FAF query failed for filter, using unfiltered snapshot: {}", e.getMessage());
                }
            }
            subscription.snapshotRows = snapshotRows;
        } finally {
            lock.unlock();
        }

        // Phase 2: Stream snapshot to client (outside lock)
        List<Map<String, Object>> rowsToSend = subscription.snapshotRows;

        for (Map<String, Object> row : rowsToSend) {
            if (!subscription.active) return;
            DataMessage msg = new DataMessage(
                    seqCounter.incrementAndGet(), "snapshot", windowName, subscriptionId, row);
            messagingTemplate.convertAndSendToUser(sessionId, destination, msg);
        }

        // Phase 3: Send snapshot_complete marker
        DataMessage completeMsg = new DataMessage(
                seqCounter.incrementAndGet(), "snapshot_complete", windowName, subscriptionId, null);
        messagingTemplate.convertAndSendToUser(sessionId, destination, completeMsg);

        // Phase 4: Drain buffer and switch to live mode
        subscription.snapshotComplete = true;

        List<DataMessage> buffered = new ArrayList<>();
        buffer.drainTo(buffered);
        for (DataMessage msg : buffered) {
            if (!subscription.active) return;
            messagingTemplate.convertAndSendToUser(sessionId, destination, msg);
        }

        log.info("Subscription {} active for window {} (session: {}, filter: {})",
                subscriptionId, windowName, sessionId, whereClause);
    }

    public void unsubscribe(String subscriptionId) {
        Subscription sub = activeSubscriptions.remove(subscriptionId);
        if (sub != null) {
            sub.active = false;
            if (sub.listener != null && sub.listenerStmt != null) {
                sub.listenerStmt.removeListener(sub.listener);
            }
            if (sub.ownsListenerStmt && sub.listenerStmt != null) {
                try {
                    String deploymentId = sub.listenerStmt.getDeploymentId();
                    esperService.getRuntime().getDeploymentService().undeploy(deploymentId);
                } catch (Exception e) {
                    log.warn("Failed to undeploy listener statement", e);
                }
            }
            log.info("Unsubscribed: {}", subscriptionId);
        }
    }

    public void unsubscribeAll(String sessionId) {
        activeSubscriptions.values().stream()
                .filter(s -> s.sessionId.equals(sessionId))
                .map(s -> s.subscriptionId)
                .toList()
                .forEach(this::unsubscribe);
    }

    private void sendOrBuffer(Subscription subscription, String sessionId,
                              String destination, BlockingQueue<DataMessage> buffer, DataMessage msg) {
        if (subscription.snapshotComplete) {
            messagingTemplate.convertAndSendToUser(sessionId, destination, msg);
        } else {
            buffer.offer(msg);
        }
    }

    private void sendError(String sessionId, String subscriptionId, String destination, String error) {
        Map<String, Object> errorData = Map.of("error", error);
        DataMessage msg = new DataMessage(0, "error", null, subscriptionId, errorData);
        messagingTemplate.convertAndSendToUser(sessionId, destination, msg);
    }

    private static class Subscription {
        final String subscriptionId;
        final String sessionId;
        final String windowName;
        final String whereClause;
        volatile boolean active = true;
        volatile boolean snapshotComplete = false;
        volatile UpdateListener listener;
        volatile EPStatement listenerStmt;
        volatile boolean ownsListenerStmt;
        volatile List<Map<String, Object>> snapshotRows;

        Subscription(String subscriptionId, String sessionId, String windowName, String whereClause) {
            this.subscriptionId = subscriptionId;
            this.sessionId = sessionId;
            this.windowName = windowName;
            this.whereClause = whereClause;
        }
    }
}
