package com.acme.enablements.activities;

import com.acme.enablements.integrations.EnablementsIntegrationsClient;
import com.acme.proto.acme.oms.v1.Order;
import com.acme.proto.acme.processing.domain.processing.v1.ValidateOrderRequest;
import com.acme.proto.acme.processing.domain.processing.v1.ValidateOrderResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommerceAppImplTest {

    @Test
    void validateOrderDelegatesToEnablementsApiClient() {
        var client = mock(EnablementsIntegrationsClient.class);
        var request = ValidateOrderRequest.newBuilder()
                .setOrder(Order.newBuilder().setOrderId("order-1").build())
                .build();
        var expected = ValidateOrderResponse.newBuilder()
                .setOrder(request.getOrder())
                .setManualCorrectionNeeded(true)
                .build();
        when(client.validateOrder(request)).thenReturn(expected);

        var response = new CommerceAppImpl(client).validateOrder(request);

        assertThat(response).isSameAs(expected);
        verify(client).validateOrder(request);
    }
}
