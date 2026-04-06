# Configuration Guide

## Window Configuration

Windows are defined as JSON files in the `config/windows/` directory (configurable via `esper.window.config.dir`). Each file defines one named window.

### JSON Schema

```json
{
    "name": "<WindowName>",
    "primaryKeys": ["<column1>", "<column2>"],
    "columns": [
        { "name": "<columnName>", "type": "<dataType>" }
    ]
}
```

### Field Reference

| Field | Required | Description |
|-------|----------|-------------|
| `name` | Yes | Unique window name. Used as the Esper named window name, event type prefix, and API identifier. Must be a valid Esper identifier (no spaces, starts with letter). |
| `primaryKeys` | Yes | Array of column names forming the primary key. Used for upsert matching and client-side row identity. |
| `columns` | Yes | Array of column definitions. Order determines display order in the client grid. |

### Supported Data Types

| Type String | Esper Type | Java Class | Example Values |
|------------|-----------|------------|---------------|
| `string` | `string` | `String` | `"AAPL"`, `"BUY"` |
| `int` / `integer` | `int` | `Integer` | `100`, `5000` |
| `long` | `long` | `Long` | `1775000000000` |
| `double` | `double` | `Double` | `178.50`, `0.01` |
| `float` | `float` | `Float` | `3.14` |
| `boolean` | `boolean` | `Boolean` | `true`, `false` |

### Included Examples

#### Orders Window (`config/windows/orders.json`)

```json
{
    "name": "Orders",
    "primaryKeys": ["orderId"],
    "columns": [
        { "name": "orderId",    "type": "string" },
        { "name": "symbol",     "type": "string" },
        { "name": "side",       "type": "string" },
        { "name": "quantity",   "type": "int" },
        { "name": "price",      "type": "double" },
        { "name": "status",     "type": "string" },
        { "name": "timestamp",  "type": "long" }
    ]
}
```

An order book with single-column primary key (`orderId`).

#### Quotes Window (`config/windows/quotes.json`)

```json
{
    "name": "Quotes",
    "primaryKeys": ["symbol"],
    "columns": [
        { "name": "symbol",     "type": "string" },
        { "name": "bid",        "type": "double" },
        { "name": "ask",        "type": "double" },
        { "name": "bidSize",    "type": "int" },
        { "name": "askSize",    "type": "int" },
        { "name": "exchange",   "type": "string" },
        { "name": "timestamp",  "type": "long" }
    ]
}
```

A quote table keyed by `symbol` — each symbol has exactly one row that gets updated.

### Adding a New Window

1. Create a new JSON file in `config/windows/`:

```json
{
    "name": "Trades",
    "primaryKeys": ["tradeId"],
    "columns": [
        { "name": "tradeId",   "type": "string" },
        { "name": "symbol",    "type": "string" },
        { "name": "price",     "type": "double" },
        { "name": "volume",    "type": "int" },
        { "name": "timestamp", "type": "long" }
    ]
}
```

2. Restart the application. The `WindowManager` scans the directory at startup.

## Application Properties

File: `src/main/resources/application.properties`

| Property | Default | Description |
|----------|---------|-------------|
| `server.port` | `8080` | HTTP server port |
| `esper.window.config.dir` | `config/windows` | Path to the directory containing window JSON files (relative to working directory) |
| `spring.jackson.serialization.indent-output` | `true` | Pretty-print JSON in REST responses |

## Build & Run

### Prerequisites

- **JDK 17+**
- **Maven 3.8+**

### Development

```bash
cd esper-window-app
mvn spring-boot:run
```

The application starts on `http://localhost:8080`. The `config/windows/` directory is resolved relative to the working directory (the project root when using `mvn spring-boot:run`).

### Production Build

```bash
mvn clean package
java -jar target/esper-window-app-1.0.0-SNAPSHOT.jar
```

When running from a JAR, ensure the `config/windows/` directory is in the same directory as the JAR, or override the path:

```bash
java -jar target/esper-window-app-1.0.0-SNAPSHOT.jar \
    --esper.window.config.dir=/path/to/config/windows
```

## Dependency Notes

### Janino Version Override

The `pom.xml` explicitly overrides Janino to version `3.1.6` and excludes the Janino versions bundled with Esper Compiler. This is necessary because Esper 8.7.0's compiler requires a specific Janino version for runtime EPL compilation:

```xml
<dependency>
    <groupId>org.codehaus.janino</groupId>
    <artifactId>janino</artifactId>
    <version>3.1.6</version>
</dependency>
```

### Esper Modules

Three Esper modules are used:

| Module | Purpose |
|--------|---------|
| `esper-common` | Shared types, configuration, event beans |
| `esper-compiler` | EPL compilation (string → compiled unit) |
| `esper-runtime` | Event processing, named windows, listeners |
