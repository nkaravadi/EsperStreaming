package com.example.esper.model;

public class SubscriptionRequest {
    private String windowName;
    private String where; // optional Esper WHERE clause

    public String getWindowName() { return windowName; }
    public void setWindowName(String windowName) { this.windowName = windowName; }
    public String getWhere() { return where; }
    public void setWhere(String where) { this.where = where; }
}
