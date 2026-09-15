package com.acme.enablements.activities;

import com.acme.enablements.commerce.CommerceCatalogFixtureService;
import com.acme.proto.acme.common.v1.Address;
import com.acme.proto.acme.common.v1.EasyPostAddress;
import com.acme.proto.acme.common.v1.EasyPostRate;
import com.acme.proto.acme.common.v1.EasyPostShipment;
import com.acme.proto.acme.common.v1.Money;
import com.acme.proto.acme.common.v1.Shipment;
import com.acme.proto.acme.enablements.domain.enablements.v1.BusinessScenario;
import com.acme.proto.acme.enablements.domain.enablements.v1.CommerceOrderState;
import com.acme.proto.acme.enablements.domain.enablements.v1.CreateChargeRequest;
import com.acme.proto.acme.enablements.domain.enablements.v1.CreateCommerceOrderRequest;
import com.acme.proto.acme.enablements.domain.enablements.v1.DemoScenario;
import com.acme.proto.acme.enablements.domain.enablements.v1.PaymentChargeState;
import com.acme.proto.acme.enablements.domain.enablements.v1.ScenarioOptions;
import com.acme.proto.acme.enablements.v1.BusinessScenarioWeight;
import com.acme.proto.acme.enablements.v1.ScenarioWeight;
import com.acme.proto.acme.enablements.v1.StartWorkerVersionEnablementRequest;
import com.acme.proto.acme.oms.v1.Item;
import com.google.protobuf.util.Durations;
import com.google.protobuf.util.JsonFormat;
import io.temporal.activity.Activity;
import io.temporal.activity.ActivityExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Component("order-activities")
public class OrderActivitiesImpl implements OrderActivities {

    private static final Logger logger = LoggerFactory.getLogger(OrderActivitiesImpl.class);

    private static final String APPROVED_TEST_CARD = "4242424242424242";

    private record CannedAddress(String street1, String city, String state, String zip, String country) {
    }

    // Must match an entry in enablements-api's shipping fixture
    // (java/enablements/enablements-api/src/main/resources/fixtures/shipping-fixtures.json)
    // or fulfillment's address verification rejects the order outright.
    private static final List<CannedAddress> CANNED_ADDRESSES = List.of(
            new CannedAddress("200 N Spring St", "Los Angeles", "CA", "90012", "US"),
            new CannedAddress("301 Congress Ave", "Austin", "TX", "78701", "US"),
            new CannedAddress("401 5th Ave", "Seattle", "WA", "98104", "US"),
            new CannedAddress("11 Wall St", "New York", "NY", "10005", "US"));

    private static final List<ScenarioWeight> DEFAULT_SCENARIO_WEIGHTS = List.of(
            ScenarioWeight.newBuilder().setScenario(DemoScenario.NORMAL).setWeight(85).build(),
            ScenarioWeight.newBuilder().setScenario(DemoScenario.PAYMENT_BEFORE_COMMERCE).setWeight(5).build(),
            ScenarioWeight.newBuilder().setScenario(DemoScenario.MISSING_COMMERCE_EVENT).setWeight(5).build(),
            ScenarioWeight.newBuilder().setScenario(DemoScenario.MISSING_PAYMENT_EVENT).setWeight(5).build());

    private static final List<BusinessScenarioWeight> DEFAULT_BUSINESS_SCENARIO_WEIGHTS = List.of(
            BusinessScenarioWeight.newBuilder().setScenario(BusinessScenario.BUSINESS_SCENARIO_NORMAL).setWeight(85).build(),
            BusinessScenarioWeight.newBuilder().setScenario(BusinessScenario.BUSINESS_SCENARIO_MARGIN_SPIKE).setWeight(5).build(),
            BusinessScenarioWeight.newBuilder().setScenario(BusinessScenario.BUSINESS_SCENARIO_SLA_BREACH).setWeight(5).build(),
            BusinessScenarioWeight.newBuilder().setScenario(BusinessScenario.BUSINESS_SCENARIO_INVALID_ORDER).setWeight(5).build());

    private static final int VALIDATION_COMPLETE_INITIAL_DELAY_SECONDS = 40;
    private static final int VALIDATION_COMPLETE_MAX_ATTEMPTS = 12;
    private static final int VALIDATION_COMPLETE_RETRY_INTERVAL_SECONDS = 5;

    private final RestClient enablementsApiClient;
    private final RestClient processingApiClient;
    private final CommerceCatalogFixtureService catalogFixture;

    public OrderActivitiesImpl(
            RestClient.Builder restClientBuilder,
            @Value("${enablements.api.base-url:http://localhost:8050}") String enablementsApiBaseUrl,
            @Value("${enablements.processing-api.base-url:http://localhost:8070}") String processingApiBaseUrl,
            CommerceCatalogFixtureService catalogFixture) {
        this.enablementsApiClient = restClientBuilder.baseUrl(enablementsApiBaseUrl).build();
        this.processingApiClient = restClientBuilder.baseUrl(processingApiBaseUrl).build();
        this.catalogFixture = catalogFixture;
    }

    @Override
    public int runSubmissionLoop(StartWorkerVersionEnablementRequest req) {
        var ctx = Activity.getExecutionContext();
        long deadline = req.hasTimeout()
                ? System.currentTimeMillis() + Durations.toMillis(req.getTimeout())
                : Long.MAX_VALUE;
        long sleepIntervalMs = req.getSubmitRatePerMin() > 0 ? (60_000L / req.getSubmitRatePerMin()) : 5_000L;
        int submitted = 0;

        while (submitted < req.getOrderCount() && System.currentTimeMillis() < deadline) {
            // Deliberately not caught: cancellation must propagate so the activity
            // execution is genuinely cancelled and the workflow recovers `submitted`
            // from the resulting CanceledFailure's heartbeat details, not a return
            // value (see WorkerVersionEnablementImpl.execute).
            ctx.heartbeat(submitted);

            var scenario = pickWeightedScenario(req.getScenarioWeightsList());
            var businessScenario = pickWeightedBusinessScenario(req.getBusinessScenarioWeightsList());
            String orderId = submitOneOrder(req, scenario, businessScenario);
            if (businessScenario == BusinessScenario.BUSINESS_SCENARIO_INVALID_ORDER) {
                completeInvalidOrderValidation(ctx, orderId, submitted);
            }
            submitted++;

            heartbeatingSleep(ctx, Duration.ofMillis(sleepIntervalMs), submitted);
        }
        return submitted;
    }

    private String submitOneOrder(StartWorkerVersionEnablementRequest req, DemoScenario scenario, BusinessScenario businessScenario) {
        var random = ThreadLocalRandom.current();
        var items = catalogFixture.fixture().items();
        var item = items.get(random.nextInt(items.size()));
        var address = CANNED_ADDRESSES.get(random.nextInt(CANNED_ADDRESSES.size()));

        String orderIdPrefix = req.hasOrderIdSeed() ? req.getOrderIdSeed() : req.getEnablementId();
        String customerId = orderIdPrefix + "-" + req.getEnablementId();

        var orderRequestBuilder = CreateCommerceOrderRequest.newBuilder()
                .setCustomerId(customerId)
                .addItems(Item.newBuilder().setItemId(item.itemId()).setQuantity(1).build())
                .setShippingAddress(Address.newBuilder()
                        .setEasypost(EasyPostAddress.newBuilder()
                                .setStreet1(address.street1())
                                .setCity(address.city())
                                .setState(address.state())
                                .setZip(address.zip())
                                .setCountry(address.country())
                                .build())
                        .build())
                .setScenarioOptions(ScenarioOptions.newBuilder().setScenario(scenario).build());
        applyBusinessScenario(orderRequestBuilder, businessScenario);
        var orderRequest = orderRequestBuilder.build();

        CommerceOrderState order = post("/api/v1/integrations/commerce/orders", orderRequest, CommerceOrderState.newBuilder());
        logger.debug("Submitted order {} (scenario={})", order.getOrderId(), scenario);

        var chargeRequest = CreateChargeRequest.newBuilder()
                .setOrderId(order.getOrderId())
                .setCustomerId(customerId)
                .setAmountCents(item.priceCents())
                .setCardNumber(APPROVED_TEST_CARD)
                .build();

        PaymentChargeState charge = post("/api/v1/integrations/payments/charges", chargeRequest, PaymentChargeState.newBuilder());
        logger.debug("Submitted charge {} for order {} (status={})", charge.getChargeId(), order.getOrderId(), charge.getStatus());

        return order.getOrderId();
    }

    private DemoScenario pickWeightedScenario(List<ScenarioWeight> configured) {
        List<ScenarioWeight> weights = configured.isEmpty() ? DEFAULT_SCENARIO_WEIGHTS : configured;
        int total = weights.stream().mapToInt(ScenarioWeight::getWeight).sum();
        if (total <= 0) {
            return DemoScenario.NORMAL;
        }
        int pick = ThreadLocalRandom.current().nextInt(total);
        int cumulative = 0;
        for (var weight : weights) {
            cumulative += weight.getWeight();
            if (pick < cumulative) {
                return weight.getScenario();
            }
        }
        return DemoScenario.NORMAL;
    }

    private BusinessScenario pickWeightedBusinessScenario(List<BusinessScenarioWeight> configured) {
        List<BusinessScenarioWeight> weights = configured.isEmpty() ? DEFAULT_BUSINESS_SCENARIO_WEIGHTS : configured;
        int total = weights.stream().mapToInt(BusinessScenarioWeight::getWeight).sum();
        if (total <= 0) {
            return BusinessScenario.BUSINESS_SCENARIO_NORMAL;
        }
        int pick = ThreadLocalRandom.current().nextInt(total);
        int cumulative = 0;
        for (var weight : weights) {
            cumulative += weight.getWeight();
            if (pick < cumulative) {
                return weight.getScenario();
            }
        }
        return BusinessScenario.BUSINESS_SCENARIO_NORMAL;
    }

    // Mirrors scripts/scenarios/invalid-order/3-complete-validation.sh: the support-team
    // workflow only records the validation request once processing.Order's Support
    // activity reaches it, so a 409 here means "not registered yet" and is retried.
    // Heartbeats through its own waits (up to ~100s total) rather than only between
    // orders, so this alone can't blow past the activity's heartbeat timeout, and a
    // pause requested mid-wait is still observed promptly.
    private void completeInvalidOrderValidation(ActivityExecutionContext ctx, String orderId, int progress) {
        heartbeatingSleep(ctx, Duration.ofSeconds(VALIDATION_COMPLETE_INITIAL_DELAY_SECONDS), progress);

        for (int attempt = 1; attempt <= VALIDATION_COMPLETE_MAX_ATTEMPTS; attempt++) {
            try {
                processingApiClient.post()
                        .uri("/api/v1/validations/{orderId}/complete", orderId)
                        .retrieve()
                        .toBodilessEntity();
                logger.debug("Completed validation for invalid order {}", orderId);
                return;
            } catch (HttpClientErrorException.Conflict e) {
                logger.debug("Validation request for order {} not yet registered (attempt {}/{})",
                        orderId, attempt, VALIDATION_COMPLETE_MAX_ATTEMPTS);
            } catch (Exception e) {
                logger.warn("Failed to complete validation for order {}: {}", orderId, e.getMessage());
                return;
            }
            heartbeatingSleep(ctx, Duration.ofSeconds(VALIDATION_COMPLETE_RETRY_INTERVAL_SECONDS), progress);
        }
        logger.warn("Gave up completing validation for order {} after {} attempts",
                orderId, VALIDATION_COMPLETE_MAX_ATTEMPTS);
    }

    // Sleeps in small heartbeating chunks so a wait longer than the heartbeat timeout
    // (the invalid-order completion wait) can't be mistaken for a dead activity, and so
    // cancellation (thrown from ctx.heartbeat) is observed promptly, not just between orders.
    private void heartbeatingSleep(ActivityExecutionContext ctx, Duration total, int progress) {
        long remainingMs = total.toMillis();
        long chunkMs = Duration.ofSeconds(5).toMillis();
        while (remainingMs > 0) {
            ctx.heartbeat(progress);
            long thisChunk = Math.min(chunkMs, remainingMs);
            try {
                Thread.sleep(thisChunk);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            remainingMs -= thisChunk;
        }
    }

    // Mirrors scripts/scenarios/{margin-spike,sla-breach,invalid-order}/1-submit-order.sh.
    private void applyBusinessScenario(CreateCommerceOrderRequest.Builder builder, BusinessScenario businessScenario) {
        switch (businessScenario) {
            case BUSINESS_SCENARIO_MARGIN_SPIKE -> builder.setSelectedShipment(Shipment.newBuilder()
                    .setPaidPrice(Money.newBuilder().setCurrency("USD").setUnits(1).build())
                    .build());
            case BUSINESS_SCENARIO_SLA_BREACH -> builder.setSelectedShipment(Shipment.newBuilder()
                    .setPaidPrice(Money.newBuilder().setCurrency("USD").setUnits(995).build())
                    .setEasypost(EasyPostShipment.newBuilder()
                            .setSelectedRate(EasyPostRate.newBuilder().setDeliveryDays(0).build())
                            .build())
                    .build());
            case BUSINESS_SCENARIO_INVALID_ORDER -> builder.setForceInvalidOrderId(true);
            default -> {
            }
        }
    }

    private <T extends com.google.protobuf.Message> T post(String path, com.google.protobuf.Message request, com.google.protobuf.Message.Builder responseBuilder) {
        try {
            String requestJson = JsonFormat.printer().print(request);
            String responseJson = enablementsApiClient.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestJson)
                    .retrieve()
                    .body(String.class);
            JsonFormat.parser().ignoringUnknownFields().merge(responseJson, responseBuilder);
            @SuppressWarnings("unchecked")
            T built = (T) responseBuilder.build();
            return built;
        } catch (Exception e) {
            throw new RuntimeException("Failed to call " + path, e);
        }
    }
}
