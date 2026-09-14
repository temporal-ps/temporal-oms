package com.acme.enablements.activities;

import com.acme.enablements.commerce.CommerceCatalogFixtureService;
import com.acme.proto.acme.common.v1.Address;
import com.acme.proto.acme.common.v1.EasyPostAddress;
import com.acme.proto.acme.common.v1.EasyPostRate;
import com.acme.proto.acme.common.v1.EasyPostShipment;
import com.acme.proto.acme.common.v1.Money;
import com.acme.proto.acme.common.v1.Shipment;
import com.acme.proto.acme.enablements.domain.enablements.v1.CommerceOrderState;
import com.acme.proto.acme.enablements.domain.enablements.v1.CreateChargeRequest;
import com.acme.proto.acme.enablements.domain.enablements.v1.CreateCommerceOrderRequest;
import com.acme.proto.acme.enablements.domain.enablements.v1.PaymentChargeState;
import com.acme.proto.acme.enablements.domain.enablements.v1.ScenarioOptions;
import com.acme.proto.acme.enablements.v1.SubmitOneOrderRequest;
import com.acme.proto.acme.enablements.v1.SubmitOneOrderResponse;
import com.acme.proto.acme.oms.v1.Item;
import com.google.protobuf.util.JsonFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

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

    private final RestClient enablementsApiClient;
    private final CommerceCatalogFixtureService catalogFixture;

    public OrderActivitiesImpl(
            RestClient.Builder restClientBuilder,
            @Value("${enablements.api.base-url:http://localhost:8050}") String enablementsApiBaseUrl,
            CommerceCatalogFixtureService catalogFixture) {
        this.enablementsApiClient = restClientBuilder.baseUrl(enablementsApiBaseUrl).build();
        this.catalogFixture = catalogFixture;
    }

    @Override
    public SubmitOneOrderResponse submitOneOrder(SubmitOneOrderRequest cmd) {
        var random = ThreadLocalRandom.current();
        var items = catalogFixture.fixture().items();
        var item = items.get(random.nextInt(items.size()));
        var address = CANNED_ADDRESSES.get(random.nextInt(CANNED_ADDRESSES.size()));

        String customerId = cmd.getOrderIdPrefix() + "-" + cmd.getEnablementId();

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
                .setScenarioOptions(ScenarioOptions.newBuilder().setScenario(cmd.getScenario()).build());
        applyBusinessScenario(orderRequestBuilder, cmd.getBusinessScenario());
        var orderRequest = orderRequestBuilder.build();

        CommerceOrderState order = post("/api/v1/integrations/commerce/orders", orderRequest, CommerceOrderState.newBuilder());
        logger.debug("Submitted order {} (scenario={})", order.getOrderId(), cmd.getScenario());

        var chargeRequest = CreateChargeRequest.newBuilder()
                .setOrderId(order.getOrderId())
                .setCustomerId(customerId)
                .setAmountCents(item.priceCents())
                .setCardNumber(APPROVED_TEST_CARD)
                .build();

        PaymentChargeState charge = post("/api/v1/integrations/payments/charges", chargeRequest, PaymentChargeState.newBuilder());
        logger.debug("Submitted charge {} for order {} (status={})", charge.getChargeId(), order.getOrderId(), charge.getStatus());

        return SubmitOneOrderResponse.newBuilder()
                .setOrderId(order.getOrderId())
                .setChargeId(charge.getChargeId())
                .build();
    }

    // Mirrors scripts/scenarios/{margin-spike,sla-breach,invalid-order}/1-submit-order.sh.
    private void applyBusinessScenario(
            CreateCommerceOrderRequest.Builder builder,
            com.acme.proto.acme.enablements.domain.enablements.v1.BusinessScenario businessScenario) {
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
