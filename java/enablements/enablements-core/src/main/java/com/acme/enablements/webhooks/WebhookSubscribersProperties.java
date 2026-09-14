package com.acme.enablements.webhooks;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Binds enablements.webhooks.subscribers: event-type -> subscriber URLs. Empty by default. */
@Component
@ConfigurationProperties(prefix = "enablements.webhooks")
public class WebhookSubscribersProperties {

    private Map<String, java.util.List<String>> subscribers = Map.of();

    public Map<String, java.util.List<String>> getSubscribers() {
        return subscribers;
    }

    public void setSubscribers(Map<String, java.util.List<String>> subscribers) {
        this.subscribers = subscribers;
    }
}
