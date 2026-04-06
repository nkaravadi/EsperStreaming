# Esper Window Application — Documentation

> Real-time event streaming and windowed data management powered by Esper CEP 8.7.0

## Documentation Index

| Document | Description |
|----------|-------------|
| [Architecture](./Architecture.md) | System architecture, component overview, and technology stack |
| [Esper Integration](./EsperIntegration.md) | How Esper CEP is configured, EPL generation, named windows, and event routing |
| [Snapshot-Streaming Design](./SnapshotStreamingDesign.md) | Deep dive into the lock-buffer-snapshot pattern that provides gap-free data delivery |
| [WebSocket & STOMP Protocol](./WebSocketProtocol.md) | WebSocket lifecycle, STOMP destinations, and message format reference |
| [REST API Reference](./RestAPI.md) | Complete REST endpoint documentation |
| [Client UI Design](./ClientDesign.md) | Browser client architecture, AG-Grid integration, and live update rendering |
| [Event Generator](./EventGenerator.md) | Random event generation system for testing and demos |
| [Configuration Guide](./Configuration.md) | Window JSON config format, application properties, and deployment |
| [Issues](./Issues.md) | Known issues and bugs found in the current implementation |
| [Execution Plan](./ExecutionPlan.md) | Prioritized plan for fixing identified issues |

## Quick Start

```bash
cd esper-window-app
mvn spring-boot:run
```

Open [http://localhost:8080](http://localhost:8080) in a browser.

1. Select a window (e.g., **Orders** or **Quotes**) from the sidebar
2. Optionally enter a WHERE clause (e.g., `price > 100`)
3. Click **Subscribe** to begin receiving data
4. Use the **Event Generator** panel to inject test events
5. Watch inserts, updates, and deletes appear in real-time in the AG-Grid

## Project Structure

```
esper-window-app/
├── pom.xml                          # Maven build (Spring Boot 3.2.5, Esper 8.7.0)
├── config/windows/                  # Window definitions (JSON)
│   ├── orders.json
│   └── quotes.json
├── src/main/java/com/example/esper/
│   ├── EsperWindowApplication.java  # Spring Boot entry point
│   ├── config/
│   │   ├── EsperConfig.java         # Esper runtime & compiler bean
│   │   └── WebSocketConfig.java     # STOMP/SockJS configuration
│   ├── model/
│   │   ├── WindowConfig.java        # Window schema POJO
│   │   ├── ColumnDef.java           # Column name + type
│   │   ├── SubscriptionRequest.java # Client subscribe payload
│   │   └── DataMessage.java         # Server→client data envelope
│   ├── service/
│   │   ├── WindowManager.java       # Loads JSON configs, creates Esper windows
│   │   ├── EsperService.java        # EPL compilation, event routing, queries
│   │   ├── SubscriptionManager.java # Snapshot+stream subscription engine
│   │   └── EventGenerator.java      # Random event generation
│   └── controller/
│       ├── WindowController.java    # REST API controller
│       └── WebSocketController.java # STOMP message handler
└── src/main/resources/
    ├── application.properties
    └── static/index.html            # Single-page browser client
```

## Documentation Notes

All documentation in this folder was authored by reviewing the actual source code. Key design details (lock ordering in `SubscriptionManager`, EPL generation in `EsperService`, listener semantics, client-side AG-Grid transaction handling) are traced directly to their implementation. The [Issues](./Issues.md) document identifies bugs confirmed through code inspection (e.g., the no-op `headerAccessor.getSessionAttributes()` call, `currentSubscriptionId` never being assigned).
