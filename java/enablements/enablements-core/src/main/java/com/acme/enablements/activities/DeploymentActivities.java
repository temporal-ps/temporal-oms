package com.acme.enablements.activities;

import com.acme.proto.acme.enablements.v1.DeployWorkerVersionRequest;
import com.acme.proto.acme.enablements.v1.DeployWorkerVersionResponse;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * Activities for promoting a bounded context to a new component version
 * (spec.md's package-per-version convention): apps, processing, or fulfillment.
 */
@ActivityInterface
public interface DeploymentActivities {

  /**
   * Deploy the requested build id for {@code cmd.getDeploymentName()} (apps, processing,
   * or fulfillment), promote it to current via {@code temporal worker deployment
   * set-current-version}, and confirm the result via {@code describe}.
   *
   * @throws RuntimeException if deployment or promotion fails
   */
  @ActivityMethod
  DeployWorkerVersionResponse deployWorkerVersion(DeployWorkerVersionRequest cmd);
}
