# Known Issues

> Issues identified through code review of the current implementation.

## Issue 1: Subscription ID Not Returned to Client (HIGH)

**Location:** `WebSocketController.subscribe()` (line ~28-36)

**Description:**  
When a client sends a STOMP subscribe message, the server creates a subscription and generates a UUID `subscriptionId`. However, this ID is **never sent back to the client**. The controller method is void and the only code after obtaining the ID is a no-op call to `headerAccessor.getSessionAttributes()`.

```java
String subscriptionId = subscriptionManager.subscribe(
        sessionId, request.getWindowName(), request.getWhere());
// Send back the subscription ID
headerAccessor.getSessionAttributes(); // ← does nothing
```

**Impact:**  
- The client's `currentSubscriptionId` variable is never set
- The **Unsubscribe** button sends `{ subscriptionId: null }` which does nothing on the server
- The user can never cleanly unsubscribe from a window; subscriptions only clean up on disconnect
- The client tracks `subscriptionId` from `DataMessage` but never uses it for unsubscribe

**Affected code:** `WebSocketController.java`, `index.html` (unsubscribe handler)

---

## Issue 2: Filtered Statement Created Outside Lock (MEDIUM-HIGH)

**Location:** `SubscriptionManager.performSubscription()` (line ~89-96)

**Description:**  
When a WHERE clause is provided, `esperService.createFilteredStatement()` is called **before** the lock is acquired. The filtered EPL statement (`select * from Window where ...`) is compiled and deployed outside the critical section. Since the listener is attached later (under the lock), events arriving between statement creation and listener attachment could be missed.

```java
// This happens BEFORE lock.lock()
if (whereClause != null && !whereClause.isBlank()) {
    listenerStmt = esperService.createFilteredStatement(windowName, whereClause);
    ownsListenerStmt = true;
}

// ... later ...
lock.lock();
try {
    listenerStmt.addListener(listener);  // Events since creation may be lost
```

**Impact:**  
In the narrow window between `createFilteredStatement()` and `addListener()`, events matching the filter could arrive and be processed by the statement without being captured by the listener. The snapshot may not include them either (depending on timing), resulting in missed events.

---

## Issue 3: Per-Window Lock Does Not Guard Event Sending (MEDIUM-HIGH)

**Location:** `EsperService.sendEvent()` and `SubscriptionManager.performSubscription()`

**Description:**  
The snapshot-streaming design relies on a `ReentrantLock` per window to ensure atomicity between the listener attachment and snapshot. However, the `sendEvent()` method does **not acquire this lock**:

```java
public void sendEvent(String eventTypeName, Map<String, Object> event) {
    runtime.getEventService().sendEventMap(event, eventTypeName);
    // ← no lock acquired
}
```

This means events can be sent to the Esper runtime and trigger listener callbacks **while the lock is held** in `performSubscription()`. The lock only serializes subscriptions against each other, not subscriptions against event delivery.

**Impact:**  
The lock-based atomicity guarantee described in the [Snapshot-Streaming Design](./SnapshotStreamingDesign.md) is weaker than intended. In practice, this may still work because:
- The listener is attached under the lock and immediately begins buffering
- The safeIterator captures a consistent point-in-time view
- But the theoretical guarantee of "no events committed while lock is held" does not hold

---

## Issue 4: FAF Query Snapshot Redundancy (LOW)

**Location:** `SubscriptionManager.performSubscription()` (line ~146-158)

**Description:**  
For filtered subscriptions, the code first iterates all rows with `safeIterator()` (unfiltered), then discards those results and re-queries using a fire-and-forget query with the filter:

```java
Iterator<EventBean> iterator = windowStmt.safeIterator();
List<Map<String, Object>> snapshotRows = new ArrayList<>();
try {
    while (iterator.hasNext()) {
        snapshotRows.add(esperService.eventBeanToMap(iterator.next()));
    }
} finally { ... }

// Then overwrites with FAF:
if (whereClause != null && !whereClause.isBlank()) {
    snapshotRows = esperService.executeQuery(windowName, whereClause);
}
```

**Impact:**  
- Wasted CPU and memory iterating the full window only to discard the results
- The FAF query (compile → deploy → iterate → undeploy) is also expensive
- Should either iterate with a filter or use FAF only, not both

---

## Issue 5: FAF Query Deploys/Undeploys a Statement (LOW-MEDIUM)

**Location:** `EsperService.executeQuery()`

**Description:**  
The `executeQuery()` method compiles a new EPL statement, deploys it, iterates its results, and undeploys it — all under the subscription lock. However, deploying/undeploying statements is a heavyweight operation in Esper and may interact with the runtime's internal locking, potentially causing deadlocks or contention at scale.

Additionally, this is done inside the per-window lock, extending the critical section duration.

---

## Issue 6: Client Row Count Inaccurate on Snapshot (LOW)

**Location:** `index.html`, `handleDataMessage()` — snapshot case

**Description:**  
During snapshot processing, each `snapshot` message increments both `stats.rows` and `stats.inserts`:

```javascript
case 'snapshot':
    if (gridApi && msg.data) {
        gridApi.applyTransaction({ add: [msg.data] });
        stats.rows++;
        stats.inserts++;  // ← snapshot rows counted as inserts
    }
    break;
```

**Impact:**  
The "Inserts" counter in the status bar includes snapshot rows, which is misleading. A window with 1000 existing rows will show 1000 inserts before any live insert occurs.

---

## Issue 7: Client Does Not Store Subscription ID from Server (LOW)

**Location:** `index.html`, subscribe click handler

**Description:**  
The client variable `currentSubscriptionId` is declared but never assigned from server responses. Even though `DataMessage` contains `subscriptionId`, the client never extracts it:

```javascript
// subscribe handler
stompClient.send('/app/subscribe', {}, JSON.stringify(request));
// currentSubscriptionId is never set

// In handleDataMessage - subscriptionId is in msg but ignored
```

Combined with Issue 1, unsubscribe is non-functional.

---

## Issue 8: No Duplicate Detection on Snapshot → Live Transition (LOW)

**Location:** `SubscriptionManager.performSubscription()` and `index.html`

**Description:**  
During the buffer drain phase, it's theoretically possible for the same row to appear in both the snapshot and the buffer (e.g., if a row was updated between the snapshot read and the snapshot_complete marker). Neither the server nor the client deduplicates these.

**Impact:**  
In AG-Grid, adding a row with the same `getRowId` twice will throw a console warning and the duplicate may be silently ignored or cause visual glitches. For updates, this is harmless (update of a just-added row). For inserts of the same PK, AG-Grid may warn.

---

## Issue 9: No Input Validation on WHERE Clause (LOW-MEDIUM)

**Location:** `EsperService.createFilteredStatement()`, `EsperService.executeQuery()`

**Description:**  
The WHERE clause from the client is passed directly into EPL compilation without sanitization. While Esper's compiler will reject syntactically invalid EPL, a malicious or malformed WHERE clause could:

- Cause compilation errors that surface as server-side exceptions
- Potentially craft subqueries or access other window data (Esper EPL injection)

---

## Issue 10: Executor Service Not Shut Down (LOW)

**Location:** `SubscriptionManager`, `EventGenerator`

**Description:**  
Both `SubscriptionManager.executor` (CachedThreadPool) and `EventGenerator.scheduler` (ScheduledThreadPool) are daemon threads but are never explicitly shut down. While daemon threads prevent JVM shutdown hang, clean shutdown with `@PreDestroy` would be more robust, ensuring in-flight operations complete gracefully.

---

## Summary

| # | Issue | Severity | Category |
|---|-------|----------|----------|
| 1 | Subscription ID not returned to client | HIGH | Functional bug |
| 2 | Filtered statement created outside lock | MEDIUM-HIGH | Concurrency |
| 3 | Per-window lock doesn't guard event sending | MEDIUM-HIGH | Concurrency (design gap) |
| 4 | Redundant unfiltered iteration before FAF query | LOW | Performance |
| 5 | FAF deploy/undeploy inside critical section | LOW-MEDIUM | Performance / risk |
| 6 | Client counts snapshot rows as inserts | LOW | UI accuracy |
| 7 | Client doesn't extract subscription ID | LOW | Functional (related to #1) |
| 8 | No snapshot → live deduplication | LOW | Correctness edge case |
| 9 | No WHERE clause input validation | LOW-MEDIUM | Security |
| 10 | Executor services not shut down | LOW | Resource management |
