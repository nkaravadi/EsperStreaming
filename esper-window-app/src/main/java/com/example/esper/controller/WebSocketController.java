package com.example.esper.controller;

import com.example.esper.model.SubscriptionRequest;
import com.example.esper.service.SubscriptionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Controller;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.Map;

@Controller
public class WebSocketController {
    private static final Logger log = LoggerFactory.getLogger(WebSocketController.class);

    private final SubscriptionManager subscriptionManager;

    public WebSocketController(SubscriptionManager subscriptionManager) {
        this.subscriptionManager = subscriptionManager;
    }

    @MessageMapping("/subscribe")
    public void subscribe(SubscriptionRequest request, SimpMessageHeaderAccessor headerAccessor) {
        String sessionId = headerAccessor.getSessionId();
        log.info("Subscribe request from session {}: window={}, where={}",
                sessionId, request.getWindowName(), request.getWhere());

        String subscriptionId = subscriptionManager.subscribe(
                sessionId, request.getWindowName(), request.getWhere());

        // Send back the subscription ID
        headerAccessor.getSessionAttributes();
    }

    @MessageMapping("/unsubscribe")
    public void unsubscribe(Map<String, String> request) {
        String subscriptionId = request.get("subscriptionId");
        if (subscriptionId != null) {
            subscriptionManager.unsubscribe(subscriptionId);
        }
    }

    @EventListener
    public void handleSessionDisconnect(SessionDisconnectEvent event) {
        String sessionId = event.getSessionId();
        log.info("WebSocket session disconnected: {}", sessionId);
        subscriptionManager.unsubscribeAll(sessionId);
    }
}
