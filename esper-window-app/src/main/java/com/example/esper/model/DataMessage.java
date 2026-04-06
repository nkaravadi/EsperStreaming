package com.example.esper.model;

import java.util.Map;

public class DataMessage {
    private long seq;
    private String type; // snapshot, insert, update, delete, snapshot_complete
    private String windowName;
    private String subscriptionId;
    private Map<String, Object> data;

    public DataMessage() {}

    public DataMessage(long seq, String type, String windowName, String subscriptionId, Map<String, Object> data) {
        this.seq = seq;
        this.type = type;
        this.windowName = windowName;
        this.subscriptionId = subscriptionId;
        this.data = data;
    }

    public long getSeq() { return seq; }
    public void setSeq(long seq) { this.seq = seq; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getWindowName() { return windowName; }
    public void setWindowName(String windowName) { this.windowName = windowName; }
    public String getSubscriptionId() { return subscriptionId; }
    public void setSubscriptionId(String subscriptionId) { this.subscriptionId = subscriptionId; }
    public Map<String, Object> getData() { return data; }
    public void setData(Map<String, Object> data) { this.data = data; }
}
