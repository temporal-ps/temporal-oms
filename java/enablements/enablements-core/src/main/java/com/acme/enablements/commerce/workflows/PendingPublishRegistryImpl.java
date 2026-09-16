package com.acme.enablements.commerce.workflows;

import com.acme.proto.acme.enablements.domain.enablements.v1.DemoScenario;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class PendingPublishRegistryImpl implements PendingPublishRegistry {

    private static final int CONTINUE_AS_NEW_EVERY_N_REGISTRATIONS = 200;

    private final Map<String, PendingPublishEntry> entries;
    private int registrationsSinceStart = 0;

    @WorkflowInit
    public PendingPublishRegistryImpl(List<PendingPublishEntry> carriedForwardEntries) {
        this.entries = new LinkedHashMap<>();
        for (var entry : carriedForwardEntries) {
            entries.put(entry.orderId(), entry);
        }
    }

    @Override
    public void execute(List<PendingPublishEntry> carriedForwardEntries) {
        Workflow.await(() -> registrationsSinceStart >= CONTINUE_AS_NEW_EVERY_N_REGISTRATIONS);
        Workflow.await(Workflow::isEveryHandlerFinished);
        var next = Workflow.newContinueAsNewStub(PendingPublishRegistry.class);
        next.execute(new ArrayList<>(entries.values()));
    }

    @Override
    public void registerCommerceOrder(String orderId, DemoScenario scenario, String commercePayloadJson) {
        var existing = entries.get(orderId);
        entries.put(orderId, new PendingPublishEntry(
                orderId,
                scenario,
                existing == null ? Optional.empty() : existing.authorizedPayloadJson(),
                existing != null && existing.authorizedDelivered(),
                Optional.of(commercePayloadJson),
                existing == null ? Optional.empty() : existing.paymentPayloadJson(),
                false,
                existing != null && existing.paymentDelivered()));
        registrationsSinceStart++;
    }

    @Override
    public void registerPaymentAuthorization(String orderId, String authorizedPayloadJson) {
        var existing = entries.get(orderId);
        entries.put(orderId, new PendingPublishEntry(
                orderId,
                existing == null ? DemoScenario.NORMAL : existing.scenario(),
                Optional.of(authorizedPayloadJson),
                false,
                existing == null ? Optional.empty() : existing.commercePayloadJson(),
                existing == null ? Optional.empty() : existing.paymentPayloadJson(),
                existing != null && existing.commerceDelivered(),
                existing != null && existing.paymentDelivered()));
        registrationsSinceStart++;
    }

    @Override
    public void registerPaymentCapture(String orderId, String paymentPayloadJson) {
        var existing = entries.get(orderId);
        entries.put(orderId, new PendingPublishEntry(
                orderId,
                existing == null ? DemoScenario.NORMAL : existing.scenario(),
                existing == null ? Optional.empty() : existing.authorizedPayloadJson(),
                existing != null && existing.authorizedDelivered(),
                existing == null ? Optional.empty() : existing.commercePayloadJson(),
                Optional.of(paymentPayloadJson),
                existing != null && existing.commerceDelivered(),
                false));
        registrationsSinceStart++;
    }

    @Override
    public void markDelivered(String orderId, PublishSide side) {
        var existing = entries.get(orderId);
        if (existing == null) {
            return;
        }
        entries.put(orderId, switch (side) {
            case AUTHORIZED -> new PendingPublishEntry(
                    existing.orderId(), existing.scenario(), existing.authorizedPayloadJson(), true,
                    existing.commercePayloadJson(), existing.paymentPayloadJson(), existing.commerceDelivered(), existing.paymentDelivered());
            case COMMERCE -> new PendingPublishEntry(
                    existing.orderId(), existing.scenario(), existing.authorizedPayloadJson(), existing.authorizedDelivered(),
                    existing.commercePayloadJson(), existing.paymentPayloadJson(), true, existing.paymentDelivered());
            case PAYMENT -> new PendingPublishEntry(
                    existing.orderId(), existing.scenario(), existing.authorizedPayloadJson(), existing.authorizedDelivered(),
                    existing.commercePayloadJson(), existing.paymentPayloadJson(), existing.commerceDelivered(), true);
        });
    }

    @Override
    public List<PendingPublishEntry> getPendingEntries() {
        return entries.values().stream()
                .filter(PendingPublishEntry::hasUndeliveredEligibleSide)
                .toList();
    }
}
