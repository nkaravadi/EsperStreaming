package com.example.esper.model;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

public class WindowConfig {
    private String name;
    private List<String> primaryKeys;
    private List<ColumnDef> columns;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public List<String> getPrimaryKeys() { return primaryKeys; }
    public void setPrimaryKeys(List<String> primaryKeys) { this.primaryKeys = primaryKeys; }
    public List<ColumnDef> getColumns() { return columns; }
    public void setColumns(List<ColumnDef> columns) { this.columns = columns; }

    public Map<String, Object> toEsperSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        for (ColumnDef col : columns) {
            schema.put(col.getName(), col.toJavaType());
        }
        return schema;
    }

    public String[] getColumnNames() {
        return columns.stream().map(ColumnDef::getName).toArray(String[]::new);
    }
}
