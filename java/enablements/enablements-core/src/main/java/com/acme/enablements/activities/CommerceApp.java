package com.acme.enablements.activities;

import com.acme.proto.acme.processing.domain.processing.v1.ValidateOrderRequest;
import com.acme.proto.acme.processing.domain.processing.v1.ValidateOrderResponse;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface CommerceApp {
    @ActivityMethod
    ValidateOrderResponse validateOrder(ValidateOrderRequest cmd);
}
