# This is an automatically generated file, please do not change
# gen by protobuf_to_pydantic[v0.3.3.1](https://github.com/so1n/protobuf_to_pydantic)
# Protobuf Version: 6.33.6 
# Pydantic Version: 2.13.0 
from ..domain.v1.commerce_p2p import BusinessScenario
from ..domain.v1.commerce_p2p import DemoScenario
from datetime import datetime
from datetime import timedelta
from enum import IntEnum
from google.protobuf.message import Message  # type: ignore
from protobuf_to_pydantic.util import Timedelta
from pydantic import BaseModel
from pydantic import BeforeValidator
from pydantic import ConfigDict
from pydantic import Field
from typing_extensions import Annotated
import typing

class RolloutStepStatus(IntEnum):
    """
     Shared by OmsVersionRolloutStep.status and OmsVersionRolloutState.overall_status:
 kept top-level, not nested in one message, since it's a status for both.
    """
    ROLLOUT_STEP_STATUS_UNSPECIFIED = 0
    PENDING = 1
    IN_PROGRESS = 2
    SUCCEEDED = 3
    FAILED = 4
    SKIPPED = 5

class ScenarioWeight(BaseModel):
    """
     Weight for one DemoScenario in the generated load's scenario mix.
    """

    model_config = ConfigDict(validate_default=True)
    scenario: DemoScenario = Field(default=0)
    weight: int = Field(default=0)

class BusinessScenarioWeight(BaseModel):
    """
     Weight for one BusinessScenario in the generated load's scenario mix.
    """

    model_config = ConfigDict(validate_default=True)
    scenario: BusinessScenario = Field(default=0)
    weight: int = Field(default=0)

class StartWorkerVersionEnablementRequest(BaseModel):
    """
     Start a worker versioning enablement demonstration
    """

    enablement_id: str = Field(default="")# e.g., "demo-session-2026-03-18"
    order_count: int = Field(default=0)# How many orders to process (e.g., 20)
    submit_rate_per_min: int = Field(default=0)# Orders per minute (e.g., 12)
    timeout: Annotated[timedelta, BeforeValidator(Timedelta.validate)] = Field(default_factory=timedelta)# How long to run (e.g., 5 minutes)
    order_id_seed: typing.Optional[str] = Field(default="")
    scenario_weights: typing.List[ScenarioWeight] = Field(default_factory=list)# weighted mix of DemoScenario; empty defaults to mostly NORMAL
    business_scenario_weights: typing.List[BusinessScenarioWeight] = Field(default_factory=list)# weighted mix of BusinessScenario; empty defaults to mostly NORMAL

class DeployWorkerVersionRequest(BaseModel):
    deployment_name: str = Field(default="")
    build_id: str = Field(default="")
    version: str = Field(default="")
    replica_count: typing.Optional[int] = Field(default=0)

class DeployWorkerVersionResponse(BaseModel):
# Fully-qualified workflow class resolved for the request's deployment_name + version,
# e.g. "com.acme.apps.workflows.v3.OrderImpl" (spec.md's package-per-version convention).
    workflow_class: str = Field(default="")
# True once `temporal worker deployment set-current-version` succeeded for this build id.
    current_version_set: bool = Field(default=False)
# Raw `temporal worker deployment describe --output json` text captured right after the
# set-current-version call, so a silent no-op can't happen unnoticed.
    describe_output: str = Field(default="")

class WorkerVersionEnablementState(BaseModel):
    """
     Current state of the worker versioning enablement demonstration
 (Order tracking is the responsibility of the OMS application, not this workflow)
    """
    class DemoPhase(IntEnum):
        """
         Workflow execution state
        """
        DEMO_PHASE_UNSPECIFIED = 0
        RUNNING_V1_ONLY = 1
        TRANSITIONING_TO_V2 = 2
        RUNNING_BOTH = 3
        COMPLETE = 4

    model_config = ConfigDict(validate_default=True)
    enablement_id: str = Field(default="")
    args: StartWorkerVersionEnablementRequest = Field(default_factory=StartWorkerVersionEnablementRequest)
    current_phase: "WorkerVersionEnablementState.DemoPhase" = Field(default=0)
# Activity metrics
    orders_submitted_count: int = Field(default=0)# How many times submitOrder() was called
    orders_per_minute: float = Field(default=0.0)# Current submission rate
    active_versions: typing.List[str] = Field(default_factory=list)# ["v1"] or ["v1", "v2"] depending on phase
# Versioning info
    last_transition_at: datetime = Field(default_factory=datetime.now)# When transitionToV2 was signaled
    deploy_requests: typing.List[DeployWorkerVersionRequest] = Field(default_factory=list)
    deployments: typing.List[DeployWorkerVersionResponse] = Field(default_factory=list)

class SubmitOneOrderRequest(BaseModel):
    """
     Submits one order to the Commerce App / Payments Processor backends
 (SPECS/commerce-payments-apps/spec.md), not directly to apps-api.
    """

    model_config = ConfigDict(validate_default=True)
    enablement_id: str = Field(default="")
    order_id_prefix: str = Field(default="")
    scenario: DemoScenario = Field(default=0)
    business_scenario: BusinessScenario = Field(default=0)

class SubmitOneOrderResponse(BaseModel):
    order_id: str = Field(default="")
    charge_id: str = Field(default="")

class OmsVersionRow(BaseModel):
    """
     One row of spec.md's OMS version -> component version mapping table
 (hosting.md "OMS-Version-Driven Promotion"). fulfillment_version is
 "embedded" for OMS versions before fulfillment existed as its own
 deployable component.
    """

    oms_version: str = Field(default="")
    apps_version: str = Field(default="")
    processing_version: str = Field(default="")
    fulfillment_version: str = Field(default="")
    description: str = Field(default="")
    future: bool = Field(default=False)

class ListOmsVersionsResponse(BaseModel):
    rows: typing.List[OmsVersionRow] = Field(default_factory=list)

class StartOmsVersionRolloutRequest(BaseModel):
    """
     Starts the OmsVersionRollout workflow for a target OMS version.
    """

    rollout_id: str = Field(default="")
    oms_version: str = Field(default="")

class OmsVersionRolloutStep(BaseModel):
    """
     One bounded-context promotion within an OMS version rollout.
    """

    model_config = ConfigDict(validate_default=True)
    bounded_context: str = Field(default="")
    target_version: str = Field(default="")
    status: RolloutStepStatus = Field(default=0)
    error_message: str = Field(default="")
    result: DeployWorkerVersionResponse = Field(default_factory=DeployWorkerVersionResponse)

class OmsVersionRolloutState(BaseModel):
    """
     Current state of the OmsVersionRollout workflow.
    """

    model_config = ConfigDict(validate_default=True)
    rollout_id: str = Field(default="")
    oms_version: str = Field(default="")
    overall_status: RolloutStepStatus = Field(default=0)
    steps: typing.List[OmsVersionRolloutStep] = Field(default_factory=list)
    error_message: str = Field(default="")

class LoadGenerationState(BaseModel):
    """
     Current state of the runSubmissionLoop Standalone Activity Execution that
 enablements-api starts, observes, and cancels directly (no owning
 workflow). Distinct from WorkerVersionEnablementState, which serves the
 separate WorkerVersionEnablement workflow path.
    """
    class ExecutionStatus(IntEnum):
        EXECUTION_STATUS_UNSPECIFIED = 0
        RUNNING = 1
        COMPLETED = 2
        CANCELED = 3
        FAILED = 4

    model_config = ConfigDict(validate_default=True)
    enablement_id: str = Field(default="")
    status: "LoadGenerationState.ExecutionStatus" = Field(default=0)
    orders_submitted_count: int = Field(default=0)# from the activity's last heartbeat details
