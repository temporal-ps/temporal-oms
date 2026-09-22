package com.acme.enablements.workflows;

import com.acme.proto.acme.enablements.v1.OmsVersionRolloutState;
import com.acme.proto.acme.enablements.v1.StartOmsVersionRolloutRequest;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Promotes apps, processing, and fulfillment to the component versions
 * spec.md's OMS version table maps to a single target OMS version
 * (hosting.md "OMS-Version-Driven Promotion"), in the order that avoids
 * landing on an unsafe intermediate combination.
 */
@WorkflowInterface
public interface OmsVersionRollout {

  @WorkflowMethod
  void execute(StartOmsVersionRolloutRequest request);

  @QueryMethod
  OmsVersionRolloutState getState();
}
