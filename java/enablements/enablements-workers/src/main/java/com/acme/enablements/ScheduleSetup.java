package com.acme.enablements;

import com.acme.enablements.commerce.workflows.PublishCartOrders;
import io.temporal.api.enums.v1.ScheduleOverlapPolicy;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.schedules.Schedule;
import io.temporal.client.schedules.ScheduleActionStartWorkflow;
import io.temporal.client.schedules.ScheduleAlreadyRunningException;
import io.temporal.client.schedules.ScheduleClient;
import io.temporal.client.schedules.ScheduleIntervalSpec;
import io.temporal.client.schedules.ScheduleOptions;
import io.temporal.client.schedules.SchedulePolicy;
import io.temporal.client.schedules.ScheduleSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Creates the PublishCartOrders Temporal Schedule idempotently at worker
 * startup, since this repo has no other Schedule and no setup-script
 * precedent for one; a fresh local clone gets it automatically.
 */
@Component
public class ScheduleSetup implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(ScheduleSetup.class);
    private static final String SCHEDULE_ID = "publish-cart-orders";

    private final ScheduleClient scheduleClient;
    private final Duration tickInterval;

    public ScheduleSetup(
            ScheduleClient scheduleClient,
            @Value("${enablements.publishing.tick-interval:PT10S}") Duration tickInterval) {
        this.scheduleClient = scheduleClient;
        this.tickInterval = tickInterval;
    }

    @Override
    public void run(ApplicationArguments args) {
        var action = ScheduleActionStartWorkflow.newBuilder()
                .setWorkflowType(PublishCartOrders.class)
                .setOptions(WorkflowOptions.newBuilder()
                        .setWorkflowId("publish-cart-orders-run")
                        .setTaskQueue("commerce")
                        .build())
                .build();

        var schedule = Schedule.newBuilder()
                .setAction(action)
                .setSpec(ScheduleSpec.newBuilder()
                        .setIntervals(List.of(new ScheduleIntervalSpec(tickInterval)))
                        .build())
                .setPolicy(SchedulePolicy.newBuilder()
                        .setOverlap(ScheduleOverlapPolicy.SCHEDULE_OVERLAP_POLICY_SKIP)
                        .build())
                .build();

        try {
            scheduleClient.createSchedule(SCHEDULE_ID, schedule, ScheduleOptions.newBuilder().build());
            logger.info("Created schedule {} with a {} interval", SCHEDULE_ID, tickInterval);
        } catch (ScheduleAlreadyRunningException e) {
            logger.info("Schedule {} already exists", SCHEDULE_ID);
        }
    }
}
