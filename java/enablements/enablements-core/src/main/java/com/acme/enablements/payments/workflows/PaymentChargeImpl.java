package com.acme.enablements.payments.workflows;

import com.acme.enablements.activities.PendingPublishRegistryActivities;
import com.acme.proto.acme.enablements.domain.enablements.v1.CaptureChargeRequest;
import com.acme.proto.acme.enablements.domain.enablements.v1.CreateChargeRequest;
import com.acme.proto.acme.enablements.domain.enablements.v1.PaymentChargeState;
import com.acme.proto.acme.enablements.domain.enablements.v1.PaymentEvent;
import com.acme.proto.acme.enablements.domain.enablements.v1.VoidChargeRequest;
import com.google.protobuf.Timestamp;
import com.google.protobuf.util.JsonFormat;
import io.temporal.activity.ActivityOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInit;

import java.time.Duration;

public class PaymentChargeImpl implements PaymentCharge {

    private static final Duration AUTO_CAPTURE_DELAY = Duration.ofSeconds(5);

    private final PendingPublishRegistryActivities registry = Workflow.newActivityStub(
            PendingPublishRegistryActivities.class,
            ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(30)).build());

    private final PaymentCardScenarios.CardOutcome outcome;
    private PaymentChargeState state;
    private boolean captureRequested = false;
    private boolean voidRequested = false;

    @WorkflowInit
    public PaymentChargeImpl(CreateChargeRequest request) {
        this.outcome = PaymentCardScenarios.lookup(request.getCardNumber());
        var builder = PaymentChargeState.newBuilder()
                .setChargeId(Workflow.getInfo().getWorkflowId())
                .setOrderId(request.getOrderId())
                .setCustomerId(request.getCustomerId())
                .setAmountCents(request.getAmountCents())
                .setCardLastFour(lastFour(request.getCardNumber()));
        if (outcome.isDeclinedAtAuthorization()) {
            builder.setStatus(outcome.outcome().name()).setDeclineReason(outcome.declineReason());
        } else {
            builder.setStatus("AUTHORIZED").setAuthorizedAt(nowTimestamp());
        }
        this.state = builder.build();
    }

    @Override
    public void execute(CreateChargeRequest request) {
        if (outcome.isDeclinedAtAuthorization()) {
            return;
        }

        registry.registerPaymentAuthorization(state.getOrderId(), toPaymentEventJson("payment.authorized"));

        Workflow.await(AUTO_CAPTURE_DELAY, () -> voidRequested || captureRequested);
        if (voidRequested) {
            state = state.toBuilder().setStatus("VOIDED").build();
            return;
        }

        if (outcome.outcome() == PaymentCardScenarios.Outcome.CAPTURE_FAILS) {
            state = state.toBuilder().setStatus("CAPTURE_FAILED").build();
            return;
        }

        state = state.toBuilder().setStatus("CAPTURED").setCapturedAt(nowTimestamp()).build();
        registry.registerPaymentCapture(state.getOrderId(), toPaymentEventJson("payment.captured"));
    }

    @Override
    public void capture(CaptureChargeRequest request) {
        requireAuthorized();
        captureRequested = true;
    }

    @Override
    public void voidCharge(VoidChargeRequest request) {
        requireAuthorized();
        voidRequested = true;
    }

    @Override
    public PaymentChargeState getState() {
        return state;
    }

    private void requireAuthorized() {
        if (!"AUTHORIZED".equals(state.getStatus())) {
            throw ApplicationFailure.newFailure(
                    "Charge " + state.getChargeId() + " is not AUTHORIZED (status=" + state.getStatus() + ")",
                    "InvalidChargeState");
        }
    }

    private String toPaymentEventJson(String type) {
        var event = PaymentEvent.newBuilder()
                .setEventId(Workflow.randomUUID().toString())
                .setType(type)
                .setCreated(nowTimestamp())
                .setCharge(state)
                .build();
        try {
            return JsonFormat.printer().print(event);
        } catch (Exception e) {
            throw ApplicationFailure.newNonRetryableFailure(
                    "Failed to serialize PaymentEvent for charge " + state.getChargeId(), "SerializationFailure");
        }
    }

    private static Timestamp nowTimestamp() {
        long millis = Workflow.currentTimeMillis();
        return Timestamp.newBuilder()
                .setSeconds(millis / 1000)
                .setNanos((int) ((millis % 1000) * 1_000_000))
                .build();
    }

    private static String lastFour(String cardNumber) {
        return cardNumber.length() <= 4 ? cardNumber : cardNumber.substring(cardNumber.length() - 4);
    }
}
