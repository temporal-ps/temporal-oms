package com.acme.enablements.webhooks;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Binds enablements.webhooks.subscribers: event-type -> subscriber endpoints. Empty by default. */
@Component
@ConfigurationProperties(prefix = "enablements.webhooks")
public class WebhookSubscribersProperties {

    private Map<String, List<Subscriber>> subscribers = Map.of();

    public Map<String, List<Subscriber>> getSubscribers() {
        return subscribers;
    }

    public void setSubscribers(Map<String, List<Subscriber>> subscribers) {
        this.subscribers = subscribers;
    }

    /**
     * One webhook destination. {@code url} may contain an {@code {orderId}} placeholder,
     * substituted with the event's order ID before delivery. {@code method} defaults to POST.
     */
    public static class Subscriber {
        private String url;
        private String method = "POST";

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getMethod() {
            return method;
        }

        public void setMethod(String method) {
            this.method = method;
        }
    }
}
