package com.example.esper.service;

import com.example.esper.config.EsperConfig;
import com.example.esper.model.ColumnDef;
import com.example.esper.model.WindowConfig;
import com.espertech.esper.common.client.EPCompiled;
import com.espertech.esper.common.client.EventBean;
import com.espertech.esper.compiler.client.CompilerArguments;
import com.espertech.esper.compiler.client.EPCompilerProvider;
import com.espertech.esper.runtime.client.EPRuntime;
import com.espertech.esper.runtime.client.EPStatement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class EsperService {
    private static final Logger log = LoggerFactory.getLogger(EsperService.class);

    private final EPRuntime runtime;
    private final EsperConfig esperConfig;
    private final Map<String, ReentrantLock> windowLocks = new ConcurrentHashMap<>();
    private final Map<String, EPStatement> windowStatements = new ConcurrentHashMap<>();

    public EsperService(EPRuntime runtime, EsperConfig esperConfig) {
        this.runtime = runtime;
        this.esperConfig = esperConfig;
    }

    public void createWindow(WindowConfig config) {
        String name = config.getName();
        windowLocks.put(name, new ReentrantLock());

        StringBuilder epl = new StringBuilder();

        // 1. Create schema for the base event type, upsert event, and delete event
        epl.append(buildCreateSchemaEPL(name + "Event", config.getColumns())).append(";\n");
        epl.append(buildCreateSchemaEPL(name + "UpsertEvent", config.getColumns())).append(";\n");

        List<ColumnDef> pkCols = config.getColumns().stream()
                .filter(c -> config.getPrimaryKeys().contains(c.getName()))
                .toList();
        epl.append(buildCreateSchemaEPL(name + "DeleteEvent", pkCols)).append(";\n");

        // 2. Create named window
        epl.append("@name('").append(name).append("-window') ");
        epl.append("create window ").append(name).append(".win:keepall() as ").append(name).append("Event;\n");

        // 3. Insert into from base event
        epl.append("insert into ").append(name).append(" select * from ").append(name).append("Event;\n");

        // 4. On-merge for upserts
        epl.append("on ").append(name).append("UpsertEvent as ue merge ").append(name).append(" as w where ");
        StringJoiner pkJoin = new StringJoiner(" and ");
        for (String pk : config.getPrimaryKeys()) {
            pkJoin.add("w." + pk + " = ue." + pk);
        }
        epl.append(pkJoin);
        epl.append(" when matched then update set ");
        StringJoiner updateJoin = new StringJoiner(", ");
        for (ColumnDef col : config.getColumns()) {
            if (!config.getPrimaryKeys().contains(col.getName())) {
                updateJoin.add(col.getName() + " = ue." + col.getName());
            }
        }
        epl.append(updateJoin);
        epl.append(" when not matched then insert select ");
        StringJoiner insertJoin = new StringJoiner(", ");
        for (ColumnDef col : config.getColumns()) {
            insertJoin.add("ue." + col.getName() + " as " + col.getName());
        }
        epl.append(insertJoin).append(";\n");

        // 5. On-delete
        epl.append("on ").append(name).append("DeleteEvent as de delete from ").append(name).append(" as w where ");
        StringJoiner delJoin = new StringJoiner(" and ");
        for (String pk : config.getPrimaryKeys()) {
            delJoin.add("w." + pk + " = de." + pk);
        }
        epl.append(delJoin).append(";\n");

        String eplStr = epl.toString();
        log.info("Deploying EPL for window {}:\n{}", name, eplStr);

        var deployment = esperConfig.compileAndDeploy(eplStr);

        // Find the named window statement by its @name annotation
        for (EPStatement stmt : deployment.getStatements()) {
            if ((name + "-window").equals(stmt.getName())) {
                windowStatements.put(name, stmt);
                log.info("Registered window statement for: {}", name);
                break;
            }
        }
    }

    private String buildCreateSchemaEPL(String typeName, List<ColumnDef> columns) {
        StringBuilder sb = new StringBuilder("@public @buseventtype create schema ");
        sb.append(typeName).append("(");
        StringJoiner colJoin = new StringJoiner(", ");
        for (ColumnDef col : columns) {
            colJoin.add(col.getName() + " " + toEsperType(col.getType()));
        }
        sb.append(colJoin).append(")");
        return sb.toString();
    }

    private String toEsperType(String type) {
        return switch (type.toLowerCase()) {
            case "string" -> "string";
            case "int", "integer" -> "int";
            case "long" -> "long";
            case "double" -> "double";
            case "float" -> "float";
            case "boolean" -> "boolean";
            default -> throw new IllegalArgumentException("Unsupported type: " + type);
        };
    }

    public void sendEvent(String eventTypeName, Map<String, Object> event) {
        runtime.getEventService().sendEventMap(event, eventTypeName);
    }

    public ReentrantLock getWindowLock(String windowName) {
        return windowLocks.get(windowName);
    }

    public EPStatement getWindowStatement(String windowName) {
        return windowStatements.get(windowName);
    }

    /**
     * Compile and deploy a filtered select on the named window for live listener subscriptions.
     */
    public EPStatement createFilteredStatement(String windowName, String whereClause) {
        String epl = "select * from " + windowName;
        if (whereClause != null && !whereClause.isBlank()) {
            epl += " where " + whereClause;
        }
        var deployment = esperConfig.compileAndDeploy(epl);
        return deployment.getStatements()[0];
    }

    /**
     * Execute a fire-and-forget query against a named window.
     * Returns results as a list of maps.
     */
    public List<Map<String, Object>> executeQuery(String windowName, String whereClause) {
        String epl = "select * from " + windowName;
        if (whereClause != null && !whereClause.isBlank()) {
            epl += " where " + whereClause;
        }
        try {
            var compiler = EPCompilerProvider.getCompiler();
            var args = new CompilerArguments(runtime.getConfigurationDeepCopy());
            args.getPath().add(runtime.getRuntimePath());
            EPCompiled compiled = compiler.compile("@name('faf') " + epl, args);
            // Deploy, iterate, then undeploy
            var deployment = runtime.getDeploymentService().deploy(compiled);
            EPStatement stmt = deployment.getStatements()[0];
            List<Map<String, Object>> rows = new ArrayList<>();
            var iterator = stmt.iterator();
            while (iterator.hasNext()) {
                rows.add(eventBeanToMap(iterator.next()));
            }
            runtime.getDeploymentService().undeploy(deployment.getDeploymentId());
            return rows;
        } catch (Exception e) {
            throw new RuntimeException("Failed to execute query: " + epl, e);
        }
    }

    public Map<String, Object> eventBeanToMap(EventBean bean) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (String prop : bean.getEventType().getPropertyNames()) {
            map.put(prop, bean.get(prop));
        }
        return map;
    }

    public EPRuntime getRuntime() {
        return runtime;
    }
}
