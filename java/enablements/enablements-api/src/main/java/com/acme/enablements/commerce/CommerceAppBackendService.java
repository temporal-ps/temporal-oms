package com.acme.enablements.commerce;

import com.acme.enablements.commerce.workflows.CommerceInventory;
import com.acme.enablements.commerce.workflows.CommerceOrder;
import com.acme.proto.acme.common.v1.Money;
import com.acme.proto.acme.enablements.domain.enablements.v1.CommerceCatalogItem;
import com.acme.proto.acme.enablements.domain.enablements.v1.CommerceOrderState;
import com.acme.proto.acme.enablements.domain.enablements.v1.CommerceShippingRateOption;
import com.acme.proto.acme.enablements.domain.enablements.v1.CreateCommerceOrderRequest;
import com.acme.proto.acme.enablements.domain.enablements.v1.GetCommerceCatalogResponse;
import com.acme.proto.acme.enablements.domain.enablements.v1.GetCommerceShippingRatesRequest;
import com.acme.proto.acme.enablements.domain.enablements.v1.GetCommerceShippingRatesResponse;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

@Service
public class CommerceAppBackendService {

    private static final Logger logger = LoggerFactory.getLogger(CommerceAppBackendService.class);

    private final WorkflowClient workflowClient;
    private final CommerceCatalogFixtureService catalogFixture;

    public CommerceAppBackendService(WorkflowClient workflowClient, CommerceCatalogFixtureService catalogFixture) {
        this.workflowClient = workflowClient;
        this.catalogFixture = catalogFixture;
    }

    public CommerceOrderState createOrder(CreateCommerceOrderRequest request) {
        String orderId = (request.getForceInvalidOrderId() ? "order-invalid-" : "order-") + UUID.randomUUID();
        logger.info("createOrder orderId={}, customerId={}", orderId, request.getCustomerId());

        CommerceOrder stub = workflowClient.newWorkflowStub(
                CommerceOrder.class,
                WorkflowOptions.newBuilder()
                        .setWorkflowId(orderId)
                        .setTaskQueue("commerce")
                        .build());
        WorkflowClient.start(stub::execute, request);
        return stub.getState();
    }

    public CommerceOrderState getOrder(String orderId) {
        return workflowClient.newWorkflowStub(CommerceOrder.class, orderId).getState();
    }

    public GetCommerceCatalogResponse getCatalog() {
        Map<String, Integer> stock = queryInventoryStock();
        var response = GetCommerceCatalogResponse.newBuilder();
        for (var item : catalogFixture.fixture().items()) {
            response.addItems(CommerceCatalogItem.newBuilder()
                    .setItemId(item.itemId())
                    .setName(item.name())
                    .setDescription(item.description())
                    .setPriceCents(item.priceCents())
                    .setImageUrl(item.imageUrl())
                    .setAvailableStock(stock.getOrDefault(item.itemId(), item.initialStock()))
                    .build());
        }
        return response.build();
    }

    public GetCommerceShippingRatesResponse getShippingRates(GetCommerceShippingRatesRequest request) {
        int itemCount = request.getItemsList().stream().mapToInt(item -> item.getQuantity()).sum();
        long standardCents = 500 + (itemCount * 50L);
        long expressCents = 1500 + (itemCount * 75L);

        return GetCommerceShippingRatesResponse.newBuilder()
                .addOptions(CommerceShippingRateOption.newBuilder()
                        .setRateId("rate-standard")
                        .setCarrier("DemoFreight")
                        .setServiceLevel("standard")
                        .setCost(Money.newBuilder().setCurrency("USD").setUnits(standardCents).build())
                        .setEstimatedDays(5)
                        .build())
                .addOptions(CommerceShippingRateOption.newBuilder()
                        .setRateId("rate-express")
                        .setCarrier("DemoFreight")
                        .setServiceLevel("express")
                        .setCost(Money.newBuilder().setCurrency("USD").setUnits(expressCents).build())
                        .setEstimatedDays(2)
                        .build())
                .build();
    }

    private Map<String, Integer> queryInventoryStock() {
        try {
            return workflowClient.newWorkflowStub(CommerceInventory.class, CommerceInventory.WORKFLOW_ID)
                    .getState()
                    .getStockByItemIdMap();
        } catch (WorkflowNotFoundException e) {
            return Map.of();
        }
    }
}
