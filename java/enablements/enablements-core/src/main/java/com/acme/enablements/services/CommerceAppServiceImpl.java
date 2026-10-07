package com.acme.enablements.services;

import com.acme.enablements.activities.CommerceApp;
import com.acme.enablements.integrations.EnablementsIntegrationsClient;
import com.acme.oms.services.CommerceAppService;
import com.acme.proto.acme.processing.domain.processing.v1.ValidateOrderRequest;
import com.acme.proto.acme.processing.domain.processing.v1.ValidateOrderResponse;
import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImpl;
import io.temporal.activity.ActivityOptions;
import io.temporal.client.ActivityClient;
import io.temporal.client.StartActivityOptions;
import io.temporal.nexus.TemporalOperationHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component("commerce-app-service")
@ServiceImpl(service = CommerceAppService.class)
public class CommerceAppServiceImpl {

    private final Logger logger = LoggerFactory.getLogger(CommerceAppServiceImpl.class);

    @OperationImpl
    public OperationHandler<ValidateOrderRequest, ValidateOrderResponse> validateOrder() {

        return TemporalOperationHandler.create(
                (context, client, request) -> {

                    logger.debug("Starting commerce app activity: " + context.getRequestId());
                    var sao = StartActivityOptions.newBuilder()
                            .setId(context.getRequestId())
                            .setTaskQueue("commerce-app")
                            .setStartToCloseTimeout(Duration.ofSeconds(30))
                            .build();
                    return client.startActivity(
                            CommerceApp.class,
                            CommerceApp::validateOrder,
                            request,
                            sao
                    );
                });
    }
}
