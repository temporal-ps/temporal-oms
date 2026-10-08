package com.acme.enablements.services;

import com.acme.enablements.integrations.EnablementsIntegrationsClient;
import com.acme.proto.acme.fulfillment.domain.fulfillment.v1.DeductInventoryRequest;
import com.acme.proto.acme.fulfillment.domain.fulfillment.v1.DeductInventoryResponse;
import com.acme.proto.acme.fulfillment.domain.fulfillment.v1.FindAlternateWarehouseRequest;
import com.acme.proto.acme.fulfillment.domain.fulfillment.v1.FindAlternateWarehouseResponse;
import com.acme.proto.acme.fulfillment.domain.fulfillment.v1.HoldItemsRequest;
import com.acme.proto.acme.fulfillment.domain.fulfillment.v1.HoldItemsResponse;
import com.acme.proto.acme.fulfillment.domain.fulfillment.v1.LookupInventoryAddressRequest;
import com.acme.proto.acme.fulfillment.domain.fulfillment.v1.LookupInventoryAddressResponse;
import com.acme.proto.acme.fulfillment.domain.fulfillment.v1.ReleaseHoldRequest;
import com.acme.proto.acme.fulfillment.domain.fulfillment.v1.ReleaseHoldResponse;
import com.acme.proto.acme.fulfillment.domain.fulfillment.v1.ReserveItemsRequest;
import com.acme.proto.acme.fulfillment.domain.fulfillment.v1.ReserveItemsResponse;
import com.acme.proto.acme.oms.v1.Order;
import com.acme.proto.acme.processing.domain.processing.v1.EnrichOrderRequest;
import com.acme.proto.acme.processing.domain.processing.v1.EnrichOrderResponse;
import com.acme.proto.acme.processing.domain.processing.v1.ValidateOrderRequest;
import com.acme.proto.acme.processing.domain.processing.v1.ValidateOrderResponse;
import io.nexusrpc.handler.OperationContext;
import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationStartDetails;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NexusIntegrationAdaptersTest {

    @Test
    void pimsAdapterDelegatesToEnablementsApiClient() throws Exception {
        var client = new RecordingClient();
        var service = new ProductInformationManagementServiceImpl(client);
        var request = EnrichOrderRequest.newBuilder()
                .setOrder(Order.newBuilder().setOrderId("order-1").build())
                .build();

        var response = invoke(service.enrichOrder(), request);

        assertThat(response.getOrder().getOrderId()).isEqualTo("order-1");
        assertThat(client.enrichOrderRequest).isSameAs(request);
    }

    @Test
    void inventoryAdapterDelegatesLifecycleOperationsToEnablementsApiClient() throws Exception {
        var client = new RecordingClient();
        var service = new InventoryServiceImpl(client);

        var lookupRequest = LookupInventoryAddressRequest.getDefaultInstance();
        var alternateRequest = FindAlternateWarehouseRequest.newBuilder()
                .setCurrentAddressId("adr_wh_east_01")
                .build();
        var holdRequest = HoldItemsRequest.newBuilder().setOrderId("order-1").build();
        var reserveRequest = ReserveItemsRequest.newBuilder().setOrderId("order-1").build();
        var deductRequest = DeductInventoryRequest.newBuilder().setOrderId("order-1").build();
        var releaseRequest = ReleaseHoldRequest.newBuilder().setOrderId("order-1").setHoldId("hold-1").build();

        invoke(service.lookupInventoryAddress(), lookupRequest);
        invoke(service.findAlternateWarehouse(), alternateRequest);
        var hold = invoke(service.holdItems(), holdRequest);
        var reserve = invoke(service.reserveItems(), reserveRequest);
        var deduct = invoke(service.deductInventory(), deductRequest);
        var release = invoke(service.releaseHold(), releaseRequest);

        assertThat(client.lookupInventoryAddressRequest).isSameAs(lookupRequest);
        assertThat(client.findAlternateWarehouseRequest).isSameAs(alternateRequest);
        assertThat(client.holdItemsRequest).isSameAs(holdRequest);
        assertThat(client.reserveItemsRequest).isSameAs(reserveRequest);
        assertThat(client.deductInventoryRequest).isSameAs(deductRequest);
        assertThat(client.releaseHoldRequest).isSameAs(releaseRequest);
        assertThat(hold.getHoldId()).isEqualTo("hold_stub_order-1");
        assertThat(reserve.getReservationId()).isEqualTo("reservation_stub_order-1");
        assertThat(deduct.getSuccess()).isTrue();
        assertThat(release.getSuccess()).isTrue();
    }

    private static <T, R> R invoke(OperationHandler<T, R> handler, T request) throws Exception {
        return handler.start(
                        OperationContext.newBuilder()
                                .setService("test-service")
                                .setOperation("test-operation")
                                .build(),
                        OperationStartDetails.newBuilder()
                                .setRequestId("test-request")
                                .build(),
                        request)
                .getSyncResult();
    }

    private static class RecordingClient implements EnablementsIntegrationsClient {

        private ValidateOrderRequest validateOrderRequest;
        private EnrichOrderRequest enrichOrderRequest;
        private LookupInventoryAddressRequest lookupInventoryAddressRequest;
        private FindAlternateWarehouseRequest findAlternateWarehouseRequest;
        private HoldItemsRequest holdItemsRequest;
        private ReserveItemsRequest reserveItemsRequest;
        private DeductInventoryRequest deductInventoryRequest;
        private ReleaseHoldRequest releaseHoldRequest;

        @Override
        public ValidateOrderResponse validateOrder(ValidateOrderRequest request) {
            this.validateOrderRequest = request;
            return ValidateOrderResponse.newBuilder()
                    .setOrder(request.getOrder())
                    .setManualCorrectionNeeded(true)
                    .build();
        }

        @Override
        public EnrichOrderResponse enrichOrder(EnrichOrderRequest request) {
            this.enrichOrderRequest = request;
            return EnrichOrderResponse.newBuilder()
                    .setOrder(request.getOrder())
                    .build();
        }

        @Override
        public LookupInventoryAddressResponse lookupInventoryAddress(LookupInventoryAddressRequest request) {
            this.lookupInventoryAddressRequest = request;
            return LookupInventoryAddressResponse.getDefaultInstance();
        }

        @Override
        public FindAlternateWarehouseResponse findAlternateWarehouse(FindAlternateWarehouseRequest request) {
            this.findAlternateWarehouseRequest = request;
            return FindAlternateWarehouseResponse.getDefaultInstance();
        }

        @Override
        public HoldItemsResponse holdItems(HoldItemsRequest request) {
            this.holdItemsRequest = request;
            return HoldItemsResponse.newBuilder()
                    .setHoldId("hold_stub_" + request.getOrderId())
                    .build();
        }

        @Override
        public ReserveItemsResponse reserveItems(ReserveItemsRequest request) {
            this.reserveItemsRequest = request;
            return ReserveItemsResponse.newBuilder()
                    .setReservationId("reservation_stub_" + request.getOrderId())
                    .build();
        }

        @Override
        public DeductInventoryResponse deductInventory(DeductInventoryRequest request) {
            this.deductInventoryRequest = request;
            return DeductInventoryResponse.newBuilder()
                    .setSuccess(true)
                    .build();
        }

        @Override
        public ReleaseHoldResponse releaseHold(ReleaseHoldRequest request) {
            this.releaseHoldRequest = request;
            return ReleaseHoldResponse.newBuilder()
                    .setSuccess(true)
                    .build();
        }
    }
}
