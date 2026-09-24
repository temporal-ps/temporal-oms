# Solution: Safely Move Fulfillment Ownership

Source spec: [spec.md](../../specs/workshop/safe-fulfillment-handoff/spec.md)  
Participant guide: [README.md](README.md)

This file contains the code solution used during the exercise. Use it as a sidecar to the
[participant guide](README.md): the README controls the exercise flow, and this file only covers
the code edits for the implementation steps.

You may apply the `processing v3` and `apps v3` code changes separately, matching the exercise
steps, or apply all code changes in one pass. The safe rollout order is still:

1. Start and promote `processing v3`.
2. Start and ramp or promote `apps v3`.

Paths and code-generation commands in this file are repo-root relative. Any command that starts
with `scripts/` means the project-root `scripts/` directory, not this exercise's local `scripts/`
directory. The exercise step scripts can still be run from
`workshop/safe-fulfillment-handoff`.

## Processing v3 Code

You are here from [README Step 4: Implement `processing v3`](README.md#4-implement-processing-v3).
After completing this section, return to
[README Step 5: Start `processing v3`](README.md#5-start-processing-v3).

Treat the processing change as a copy/paste safety patch. The goal is not to make attendees reason
through Java control flow; the goal is to preserve the old Kafka handoff unless a caller explicitly
opts out.

### 1. Add The Routing Slip Field


Edit
[proto/acme/processing/domain/v1/workflows.proto](../../proto/acme/processing/domain/v1/workflows.proto):

```proto
message ProcessOrderRequestExecutionOptions {
  optional int64 processing_timeout_secs = 1;
  optional acme.oms.v1.OmsProperties oms_properties = 2;
  // WORKSHOP: the send_fulfillment field is added to allow callers
  // to forward processing.Order data downstream to Fulfillment.
  
  // DO NOT DUPLICATE `send_fulfillment` FIELD if it is already present. 
  optional bool send_fulfillment = 3;
}
```

Regenerate protobuf outputs with project-root
[scripts/generate.sh](../../scripts/generate.sh):

```bash
scripts/generate.sh
```

If your terminal is still in the exercise directory from the participant guide, run:

```bash
../../scripts/generate.sh
```

Do not hand-edit generated files.

### 2. Add The Compatibility Guard

File:
[java/processing/processing-core/src/main/java/com/acme/processing/workflows/v2/OrderImpl.java](../../java/processing/processing-core/src/main/java/com/acme/processing/workflows/v2/OrderImpl.java)

Find the request options block near the top of `execute(...)`:

```java
var opts = request.hasOptions()
        ? request.getOptions()
        : ProcessOrderRequestExecutionOptions.getDefaultInstance();
if (!opts.hasOmsProperties()) {
    opts = this.optionsActs.getOptions(opts);
}
```

Paste this immediately after it:

```java
// WORKSHOP: Exercise 01: missing send_fulfillment means legacy callers still use Kafka fulfillment.
boolean sendFulfillment =
        !opts.hasSendFulfillment() || opts.getSendFulfillment();
```

What this does: `apps v2` does not know about `send_fulfillment`, so `processing v3` treats the
missing field as `true` and keeps the legacy handoff working.

### 3. Wrap The Legacy Kafka Handoff

In the same file, find the existing `try/catch` block that calls
`this.fulfillments.fulfillOrder(...)`. It appears after order enrichment inside the cancellation
scope.

Replace only that `try/catch` block with this guarded version:

```java
// Exercise 01: only processing-owned fulfillment publishes the legacy Kafka handoff.
if (sendFulfillment) {
    try {
        this.state = this.state.toBuilder()
                .setFulfillment(this.fulfillments.fulfillOrder(
                        FulfillOrderRequest.newBuilder()
                                .setOrder(request.getOrder())
                                .addAllItems(this.state.getEnrichment().getItemsList())
                                .build()))
                .build();
    } catch (ApplicationFailure e) {
        if (e.isNonRetryable()) {
            // permanent failure handling remains unchanged
        }
    }
}
```

What this does: old callers still get Kafka fulfillment; new callers can set
`send_fulfillment=false` and move fulfillment ownership to `apps.Order`.

### 4. Update The Timeout Guard

Find the timer block immediately after the cancellation scope:

```java
Workflow.newTimer(Duration.ofSeconds(timeoutSecs)).thenApply(result -> {
    if (!state.hasFulfillment()) {
        scope.cancel();
    }
    return null;
});
```

Replace the condition with this:

```java
Workflow.newTimer(Duration.ofSeconds(timeoutSecs)).thenApply(result -> {
    if (!state.hasEnrichment() || (sendFulfillment && !state.hasFulfillment())) {
        scope.cancel();
    }
    return null;
});
```

What this does: when processing is no longer responsible for fulfillment, enrichment is enough for
`processing.Order` to complete successfully.

Do not use `Workflow.getVersion` for this handoff. Pinned Worker Versioning keeps old executions on
old code; the routing slip records the per-order contract.

Return to [README Step 5: Start `processing v3`](README.md#5-start-processing-v3).

## Apps v3 Code

You are here from [README Step 7: Implement `apps v3`](README.md#7-implement-apps-v3).
After completing this section, return to
[README Step 8: Start Fulfillment Workers For The New Path](README.md#8-start-fulfillment-workers-for-the-new-path).

File:
[java/apps/apps-core/src/main/java/com/acme/apps/workflows/v2/OrderImpl.java](../../java/apps/apps-core/src/main/java/com/acme/apps/workflows/v2/OrderImpl.java)

The Java-heavy fulfillment code is already in private helper methods at the bottom of this class.
Those helpers have `WORKSHOP Exercise 01` comments explaining what they do. The exercise is to make
the `execute(...)` path call them and to set the routing slip sent to processing.

### 1. Configure The Fulfillment Nexus Stub

Find this `WORKSHOP` marker after the existing processing Nexus stub:

```java
// WORKSHOP Exercise 01: apps v3 also configures the fulfillment Nexus stub here:
// configureFulfillmentNexusStub(remainingTime);
```

Uncomment the method call:

```java
configureFulfillmentNexusStub(remainingTime);
```

What this does: `apps.Order` now has a Nexus client for `fulfillment.Order`, but no fulfillment work
has started yet.

### 2. Start Fulfillment Validation Before Processing

Find this `WORKSHOP` marker after the cancellation check:

```java
// WORKSHOP Exercise 01: apps v3 starts fulfillment.Order validation before processing:
// var validatePromise = startFulfillmentValidation();
```

Uncomment the method call:

```java
var validatePromise = startFulfillmentValidation();
```

What this does: `apps.Order` starts `fulfillment.Order` and validates the shipping address while
`processing.Order` validates and enriches the order.

### 3. Send The Routing Slip - Coding Activity

In `tryScheduleProcessOrder()`, find the processing request options builder:

```java
.setOptions(ProcessOrderRequestExecutionOptions.newBuilder()
        .setProcessingTimeoutSecs(
                state.getOptions().getProcessingTimeoutSecs()
        ).build())).build();
```

Add the routing slip line before `.build()`:

```java
.setOptions(ProcessOrderRequestExecutionOptions.newBuilder()
        .setProcessingTimeoutSecs(
                state.getOptions().getProcessingTimeoutSecs()
        )
        .setSendFulfillment(false)
        .build())).build();
```

This is the important handoff contract: `apps v3` tells `processing v3` not to publish the legacy
Kafka fulfillment handoff.

### 4. Finish Fulfillment After Processing

Find this `WORKSHOP` marker in the success block after `scope.run()`:

```java
// WORKSHOP Exercise 01: apps v3 completes fulfillment after processing succeeds:
// finishFulfillmentAfterProcessing(validatePromise);
```

Uncomment the method call:

```java
finishFulfillmentAfterProcessing(validatePromise);
```

What this does: after processing enriches the order, `apps.Order` waits for fulfillment validation
and then sends enriched items to `fulfillment.Order`.

The behavior to verify in Temporal UI is that new `ProcessOrderRequest` inputs visibly include
`send_fulfillment=false`, and new-path orders create `fulfillment.Order` workflows instead of Kafka
handoff records.

Return to
[README Step 8: Start Fulfillment Workers For The New Path](README.md#8-start-fulfillment-workers-for-the-new-path).

## Worker Deployment Properties

Both worker configs must use Temporal Worker Deployment properties. The build ID is runtime
configuration, not a source edit.

Apps config:
[java/apps/apps-core/src/main/resources/acme.apps.yaml](../../java/apps/apps-core/src/main/resources/acme.apps.yaml)

```yaml
deployment-properties:
  use-versioning: true
  default-versioning-behavior: PINNED
  deployment-name: ${TEMPORAL_DEPLOYMENT_NAME:apps}
  build-id: ${TEMPORAL_WORKER_BUILD_ID:local}
```

Processing config:
[java/processing/processing-core/src/main/resources/acme.processing.yaml](../../java/processing/processing-core/src/main/resources/acme.processing.yaml)

```yaml
deployment-properties:
  use-versioning: true
  default-versioning-behavior: PINNED
  deployment-name: ${TEMPORAL_DEPLOYMENT_NAME:processing}
  build-id: ${TEMPORAL_WORKER_BUILD_ID:local}
```

Rules:

- Same Temporal task queue across versions.
- Same `TEMPORAL_DEPLOYMENT_NAME` across versions of one service.
- Different `TEMPORAL_WORKER_BUILD_ID` for each worker version.
- Unique local HTTP and management ports only because multiple JVMs run on one machine.

## Build And Run Commands

The participant guide uses exercise-specific scripts for these steps. The commands below are the
manual equivalent that those scripts wrap.

Build processing after applying `processing v3`:

```bash
cd java
mvn -pl processing/processing-workers -am -DskipTests install
cd ..
```

Run `processing v3`:

```bash
ACME_PROCESSING_ORDER_WORKFLOW_CLASS=com.acme.processing.workflows.v2.OrderImpl \
TEMPORAL_DEPLOYMENT_NAME=processing \
TEMPORAL_WORKER_BUILD_ID=v3 \
java -jar java/processing/processing-workers/target/processing-workers-1.0.0-SNAPSHOT.jar \
  --server.port=8072 \
  --management.server.port=9083
```

Build apps after applying `apps v3`:

```bash
cd java
mvn -pl apps/apps-workers -am -DskipTests install
cd ..
```

Run `apps v3`:

```bash
ACME_APPS_ORDER_WORKFLOW_CLASS=com.acme.apps.workflows.v2.OrderImpl \
TEMPORAL_DEPLOYMENT_NAME=apps \
TEMPORAL_WORKER_BUILD_ID=v3 \
java -jar java/apps/apps-workers/target/apps-workers-1.0.0-SNAPSHOT.jar \
  --server.port=8082 \
  --management.server.port=9093
```

## Traffic Generator

The exercise uses the existing enablements load generator instead of one-off scenario scripts.

```bash
export ENABLEMENT_ID="safe-handoff-$(date +%Y%m%d%H%M%S)"

temporal workflow start \
  --task-queue enablements \
  --type WorkerVersionEnablement \
  --workflow-id "${ENABLEMENT_ID}" \
  --namespace default \
  --input "{\"enablementId\":\"${ENABLEMENT_ID}\",\"orderCount\":1000,\"submitRatePerMin\":12,\"timeout\":\"900s\",\"orderIdSeed\":\"order\"}" \
  --input-meta 'encoding=json/protobuf'
```

Generated order IDs follow:

```text
{order_id_seed}-{enablement_id}-{timestamp_millis}
```

For this exercise, order IDs start with `order-${ENABLEMENT_ID}`.

## Verification

Minimum acceptance checks:

- `processing v3` with no `send_fulfillment` option still calls `Fulfillments.fulfillOrder`.
- `processing v3` with `send_fulfillment=false` skips `Fulfillments.fulfillOrder`.
- `apps v3` creates a `ProcessOrderRequest` whose options include `send_fulfillment=false`.
- A new-path order creates a `fulfillment.Order` execution.
- A new-path order does not create a Kafka fulfillment record.
- Old pinned `apps.Order` and `processing.Order` executions complete on their original build IDs.
