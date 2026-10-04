package com.acme.oms.services;

import com.acme.proto.acme.apps.domain.apps.v1.CapturePaymentRequest;
import com.acme.proto.acme.apps.domain.apps.v1.GetCompleteOrderStateResponse;
import com.acme.proto.acme.apps.domain.apps.v1.SubmitOrderRequest;
import io.nexusrpc.Operation;
import io.nexusrpc.Service;

@Service
public interface AppsService {
    @Operation
    GetCompleteOrderStateResponse capturePayment(CapturePaymentRequest request);

    @Operation
    GetCompleteOrderStateResponse submitOrder(SubmitOrderRequest request);
}
