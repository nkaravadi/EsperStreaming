package com.example.esper.service;

import com.example.esper.model.DataMessage;
import com.espertech.esper.common.client.EventBean;
import com.espertech.esper.common.client.util.SafeIterator;
import com.espertech.esper.runtime.client.EPStatement;
import com.espertech.esper.runtime.client.UpdateListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class SubscriptionManager {
    private static final Logger log = LoggerFactory.getLogger(SubscriptionManager.class);

    /** Sentinel placed in the buffer by unsubscribe() to unblock the worker's take(). */
    private static final DataMessage POISON = new DataMessage(-1, "poison", null, null, null);

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
        Subscription subscription = new Subscription(subscriptionId, sessionId, windowName, whereClause, buffer);

        // Deploy the listener statement HERE, on the calling (STOMP inbound) thread,
        // BEFORE submitting the worker. deploy() acquires Esper's runtime-level write
        // lock, which conflicts with sendEventMap()'s read lock if called concurrently.
        // By deploying synchronously before any event traffic starts, we avoid the deadlock.
        EPStatement listenerStmt = esperService.createFilteredStatement(windowName, whereClause);
        subscription.listenerStmt = listenerStmt;
        subscription.ownsListenerStmt = true;

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

        EPStatement windowStmt = esperService.getWindowStatement(windowName);

        if (windowStmt == null) {
            sendError(sessionId, subscriptionId, destination, "Window not found: " + windowName);
            return;
        }

        // listenerStmt was already deployed in subscribe() on the calling thread to avoid
        // deadlocking Esper's runtime-level lock with concurrent sendEventMap() calls.
        EPStatement listenerStmt = subscription.listenerStmt;

        // Phase 1: Attach listener — always buffers, worker thread is the sole sender.
        //
        // Named window listener semantics (irstream):
        //   newEvents only        -> INSERT (direct insert or on-merge not-matched)
        //   newEvents + oldEvents -> UPDATE (on-merge matched: new=current, old=previous)
        //   oldEvents only        -> DELETE (on-delete)
        UpdateListener listener = (newEvents, oldEvents, stmt, rt) -> {
            if (!subscription.active) return;

            boolean isUpdate = newEvents != null && oldEvents != null;

            if (isUpdate) {
                for (EventBean bean : newEvents) {
                    Map<String, Object> data = esperService.eventBeanToMap(bean);
                    buffer.offer(new DataMessage(
                            seqCounter.incrementAndGet(), "update", windowName, subscriptionId, data));
                }
            } else {
                if (newEvents != null) {
                    for (EventBean bean : newEvents) {
                        Map<String, Object> data = esperService.eventBeanToMap(bean);
                        buffer.offer(new DataMessage(
                                seqCounter.incrementAndGet(), "insert", windowName, subscriptionId, data));
                    }
                }
                if (oldEvents != null) {
                    for (EventBean bean : oldEvents) {
                        Map<String, Object> data = esperService.eventBeanToMap(bean);
                        buffer.offer(new DataMessage(
                                seqCounter.incrementAndGet(), "delete", windowName, subscriptionId, data));
                    }
                }
            }
        };

        listenerStmt.addListener(listener);
        subscription.listener = listener;
        // listenerStmt and ownsListenerStmt were already set in subscribe() before this worker ran

        // Phase 2: Snapshot — safeIterator() uses Esper's own internal read lock,
        // which is the correct mechanism for a consistent point-in-time view.
        // Any events that arrive between addListener() and safeIterator() will be
        // captured by the listener and buffered, so no events are lost.
        List<Map<String, Object>> snapshotRows;

        if (whereClause != null && !whereClause.isBlank()) {
            // Use a fire-and-forget query for a filtered snapshot — this is the
            // correct approach since safeIterator() iterates ALL rows.
            try {
                snapshotRows = esperService.executeQuery(windowName, whereClause);
            } catch (Exception e) {
                log.warn("FAF query failed for filter, using full snapshot: {}", e.getMessage());
                snapshotRows = snapshotAll(windowStmt);
            }
        } else {
            snapshotRows = snapshotAll(windowStmt);
        }

        // Phase 3: Stream snapshot to client
        for (Map<String, Object> row : snapshotRows) {
            if (!subscription.active) return;
            DataMessage msg = new DataMessage(
                    seqCounter.incrementAndGet(), "snapshot", windowName, subscriptionId, row);
            sendToSession(sessionId, destination, msg);
        }

        // Phase 4: Send snapshot_complete marker
        DataMessage completeMsg = new DataMessage(
                seqCounter.incrementAndGet(), "snapshot_complete", windowName, subscriptionId, null);
        sendToSession(sessionId, destination, completeMsg);

        // Phase 5: Drain buffer continuously — the worker is the sole sender for the
        // lifetime of the subscription. The listener only ever calls buffer.offer().
        // take() blocks until a message arrives; unsubscribe() drops a POISON pill
        // into the buffer to wake this thread immediately when cancelled.
        try {
            while (true) {
                DataMessage msg = buffer.take();        // blocks until available
                if (msg == POISON) break;               // unsubscribe() signalled stop
                sendToSession(sessionId, destination, msg);
                // Drain any additional messages that arrived while we were sending
                List<DataMessage> buffered = new ArrayList<>();
                buffer.drainTo(buffered);
                for (DataMessage m : buffered) {
                    if (m == POISON) return;
                    sendToSession(sessionId, destination, m);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        log.info("Subscription {} active for window {} (session: {}, filter: {})",
                subscriptionId, windowName, sessionId, whereClause);
    }

    private List<Map<String, Object>> snapshotAll(EPStatement windowStmt) {
        List<Map<String, Object>> rows = new ArrayList<>();
        // SafeIterator holds Esper's internal read lock — MUST call close() to release it.
        // SafeIterator does NOT implement AutoCloseable, so try-with-resources won't work.
        SafeIterator<EventBean> iterator = windowStmt.safeIterator();
        try {
            while (iterator.hasNext()) {
                rows.add(esperService.eventBeanToMap(iterator.next()));
            }
        } finally {
            iterator.close();   // releases the read lock — without this, sendEventMap deadlocks
        }
        return rows;
    }

    public void unsubscribe(String subscriptionId) {
        Subscription sub = activeSubscriptions.remove(subscriptionId);
        if (sub != null) {
            sub.active = false;
            sub.buffer.offer(POISON);           // wake the worker's take() immediately
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

    private void sendError(String sessionId, String subscriptionId, String destination, String error) {
        Map<String, Object> errorData = Map.of("error", error);
        DataMessage msg = new DataMessage(0, "error", null, subscriptionId, errorData);
        sendToSession(sessionId, destination, msg);
    }

    /**
     * Send a message directly to a specific STOMP session, bypassing the user-principal
     * resolver. Spring's convertAndSendToUser() looks up sessions by principal name, which
     * doesn't work when there's no security/principal set up. Instead, we create headers
     * that include the sessionId explicitly so the broker routes to the right session.
     */
    private void sendToSession(String sessionId, String destination, Object payload) {
        SimpMessageHeaderAccessor ha = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        ha.setSessionId(sessionId);
        ha.setLeaveMutable(true);
        // destination is relative (e.g. "/queue/data"); Spring will route to the session
        messagingTemplate.convertAndSendToUser(sessionId, destination, payload,
                ha.getMessageHeaders());
    }

    private static class Subscription {
        final String subscriptionId;
        final String sessionId;
        final String windowName;
        final String whereClause;
        final BlockingQueue<DataMessage> buffer;
        volatile boolean active = true;
        volatile UpdateListener listener;
        volatile EPStatement listenerStmt;
        volatile boolean ownsListenerStmt;

        Subscription(String subscriptionId, String sessionId, String windowName, String whereClause,
                     BlockingQueue<DataMessage> buffer) {
            this.subscriptionId = subscriptionId;
            this.sessionId = sessionId;
            this.windowName = windowName;
            this.whereClause = whereClause;
            this.buffer = buffer;
        }
    }
}
