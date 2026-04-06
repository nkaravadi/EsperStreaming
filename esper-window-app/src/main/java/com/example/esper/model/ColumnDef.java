package com.example.esper.model;

public class ColumnDef {
    private String name;
    private String type;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public Class<?> toJavaType() {
        return switch (type.toLowerCase()) {
            case "string" -> String.class;
            case "int", "integer" -> Integer.class;
            case "long" -> Long.class;
            case "double" -> Double.class;
            case "float" -> Float.class;
            case "boolean" -> Boolean.class;
            default -> throw new IllegalArgumentException("Unsupported type: " + type);
        };
    }
}
