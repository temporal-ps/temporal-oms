package com.acme.enablements.activities;

import com.acme.enablements.integrations.EnablementsIntegrationsClient;
import com.acme.proto.acme.processing.domain.processing.v1.ValidateOrderRequest;
import com.acme.proto.acme.processing.domain.processing.v1.ValidateOrderResponse;
import org.springframework.stereotype.Component;

@Component("commerce-app-activities")
public class CommerceAppImpl implements CommerceApp{

    private EnablementsIntegrationsClient enablementsClient;

    public CommerceAppImpl(EnablementsIntegrationsClient enablementsClient) {
        this.enablementsClient = enablementsClient;
    }


    @Override
    public ValidateOrderResponse validateOrder(ValidateOrderRequest cmd) {
        return enablementsClient.validateOrder(cmd);
    }

}
