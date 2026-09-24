import datetime

from google.protobuf import duration_pb2 as _duration_pb2
from google.protobuf import timestamp_pb2 as _timestamp_pb2
from acme.enablements.domain.v1 import commerce_pb2 as _commerce_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf.internal import enum_type_wrapper as _enum_type_wrapper
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class RolloutStepStatus(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    ROLLOUT_STEP_STATUS_UNSPECIFIED: _ClassVar[RolloutStepStatus]
    PENDING: _ClassVar[RolloutStepStatus]
    IN_PROGRESS: _ClassVar[RolloutStepStatus]
    SUCCEEDED: _ClassVar[RolloutStepStatus]
    FAILED: _ClassVar[RolloutStepStatus]
    SKIPPED: _ClassVar[RolloutStepStatus]
ROLLOUT_STEP_STATUS_UNSPECIFIED: RolloutStepStatus
PENDING: RolloutStepStatus
IN_PROGRESS: RolloutStepStatus
SUCCEEDED: RolloutStepStatus
FAILED: RolloutStepStatus
SKIPPED: RolloutStepStatus

class StartWorkerVersionEnablementRequest(_message.Message):
    __slots__ = ()
    ENABLEMENT_ID_FIELD_NUMBER: _ClassVar[int]
    ORDER_COUNT_FIELD_NUMBER: _ClassVar[int]
    SUBMIT_RATE_PER_MIN_FIELD_NUMBER: _ClassVar[int]
    TIMEOUT_FIELD_NUMBER: _ClassVar[int]
    ORDER_ID_SEED_FIELD_NUMBER: _ClassVar[int]
    SCENARIO_WEIGHTS_FIELD_NUMBER: _ClassVar[int]
    BUSINESS_SCENARIO_WEIGHTS_FIELD_NUMBER: _ClassVar[int]
    enablement_id: str
    order_count: int
    submit_rate_per_min: int
    timeout: _duration_pb2.Duration
    order_id_seed: str
    scenario_weights: _containers.RepeatedCompositeFieldContainer[ScenarioWeight]
    business_scenario_weights: _containers.RepeatedCompositeFieldContainer[BusinessScenarioWeight]
    def __init__(self, enablement_id: _Optional[str] = ..., order_count: _Optional[int] = ..., submit_rate_per_min: _Optional[int] = ..., timeout: _Optional[_Union[datetime.timedelta, _duration_pb2.Duration, _Mapping]] = ..., order_id_seed: _Optional[str] = ..., scenario_weights: _Optional[_Iterable[_Union[ScenarioWeight, _Mapping]]] = ..., business_scenario_weights: _Optional[_Iterable[_Union[BusinessScenarioWeight, _Mapping]]] = ...) -> None: ...

class ScenarioWeight(_message.Message):
    __slots__ = ()
    SCENARIO_FIELD_NUMBER: _ClassVar[int]
    WEIGHT_FIELD_NUMBER: _ClassVar[int]
    scenario: _commerce_pb2.DemoScenario
    weight: int
    def __init__(self, scenario: _Optional[_Union[_commerce_pb2.DemoScenario, str]] = ..., weight: _Optional[int] = ...) -> None: ...

class BusinessScenarioWeight(_message.Message):
    __slots__ = ()
    SCENARIO_FIELD_NUMBER: _ClassVar[int]
    WEIGHT_FIELD_NUMBER: _ClassVar[int]
    scenario: _commerce_pb2.BusinessScenario
    weight: int
    def __init__(self, scenario: _Optional[_Union[_commerce_pb2.BusinessScenario, str]] = ..., weight: _Optional[int] = ...) -> None: ...

class WorkerVersionEnablementState(_message.Message):
    __slots__ = ()
    class DemoPhase(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
        __slots__ = ()
        DEMO_PHASE_UNSPECIFIED: _ClassVar[WorkerVersionEnablementState.DemoPhase]
        RUNNING_V1_ONLY: _ClassVar[WorkerVersionEnablementState.DemoPhase]
        TRANSITIONING_TO_V2: _ClassVar[WorkerVersionEnablementState.DemoPhase]
        RUNNING_BOTH: _ClassVar[WorkerVersionEnablementState.DemoPhase]
        COMPLETE: _ClassVar[WorkerVersionEnablementState.DemoPhase]
    DEMO_PHASE_UNSPECIFIED: WorkerVersionEnablementState.DemoPhase
    RUNNING_V1_ONLY: WorkerVersionEnablementState.DemoPhase
    TRANSITIONING_TO_V2: WorkerVersionEnablementState.DemoPhase
    RUNNING_BOTH: WorkerVersionEnablementState.DemoPhase
    COMPLETE: WorkerVersionEnablementState.DemoPhase
    ENABLEMENT_ID_FIELD_NUMBER: _ClassVar[int]
    ARGS_FIELD_NUMBER: _ClassVar[int]
    CURRENT_PHASE_FIELD_NUMBER: _ClassVar[int]
    ORDERS_SUBMITTED_COUNT_FIELD_NUMBER: _ClassVar[int]
    ORDERS_PER_MINUTE_FIELD_NUMBER: _ClassVar[int]
    ACTIVE_VERSIONS_FIELD_NUMBER: _ClassVar[int]
    LAST_TRANSITION_AT_FIELD_NUMBER: _ClassVar[int]
    DEPLOY_REQUESTS_FIELD_NUMBER: _ClassVar[int]
    DEPLOYMENTS_FIELD_NUMBER: _ClassVar[int]
    enablement_id: str
    args: StartWorkerVersionEnablementRequest
    current_phase: WorkerVersionEnablementState.DemoPhase
    orders_submitted_count: int
    orders_per_minute: float
    active_versions: _containers.RepeatedScalarFieldContainer[str]
    last_transition_at: _timestamp_pb2.Timestamp
    deploy_requests: _containers.RepeatedCompositeFieldContainer[DeployWorkerVersionRequest]
    deployments: _containers.RepeatedCompositeFieldContainer[DeployWorkerVersionResponse]
    def __init__(self, enablement_id: _Optional[str] = ..., args: _Optional[_Union[StartWorkerVersionEnablementRequest, _Mapping]] = ..., current_phase: _Optional[_Union[WorkerVersionEnablementState.DemoPhase, str]] = ..., orders_submitted_count: _Optional[int] = ..., orders_per_minute: _Optional[float] = ..., active_versions: _Optional[_Iterable[str]] = ..., last_transition_at: _Optional[_Union[datetime.datetime, _timestamp_pb2.Timestamp, _Mapping]] = ..., deploy_requests: _Optional[_Iterable[_Union[DeployWorkerVersionRequest, _Mapping]]] = ..., deployments: _Optional[_Iterable[_Union[DeployWorkerVersionResponse, _Mapping]]] = ...) -> None: ...

class SubmitOneOrderRequest(_message.Message):
    __slots__ = ()
    ENABLEMENT_ID_FIELD_NUMBER: _ClassVar[int]
    ORDER_ID_PREFIX_FIELD_NUMBER: _ClassVar[int]
    SCENARIO_FIELD_NUMBER: _ClassVar[int]
    BUSINESS_SCENARIO_FIELD_NUMBER: _ClassVar[int]
    enablement_id: str
    order_id_prefix: str
    scenario: _commerce_pb2.DemoScenario
    business_scenario: _commerce_pb2.BusinessScenario
    def __init__(self, enablement_id: _Optional[str] = ..., order_id_prefix: _Optional[str] = ..., scenario: _Optional[_Union[_commerce_pb2.DemoScenario, str]] = ..., business_scenario: _Optional[_Union[_commerce_pb2.BusinessScenario, str]] = ...) -> None: ...

class SubmitOneOrderResponse(_message.Message):
    __slots__ = ()
    ORDER_ID_FIELD_NUMBER: _ClassVar[int]
    CHARGE_ID_FIELD_NUMBER: _ClassVar[int]
    order_id: str
    charge_id: str
    def __init__(self, order_id: _Optional[str] = ..., charge_id: _Optional[str] = ...) -> None: ...

class DeployWorkerVersionRequest(_message.Message):
    __slots__ = ()
    DEPLOYMENT_NAME_FIELD_NUMBER: _ClassVar[int]
    BUILD_ID_FIELD_NUMBER: _ClassVar[int]
    VERSION_FIELD_NUMBER: _ClassVar[int]
    REPLICA_COUNT_FIELD_NUMBER: _ClassVar[int]
    deployment_name: str
    build_id: str
    version: str
    replica_count: int
    def __init__(self, deployment_name: _Optional[str] = ..., build_id: _Optional[str] = ..., version: _Optional[str] = ..., replica_count: _Optional[int] = ...) -> None: ...

class DeployWorkerVersionResponse(_message.Message):
    __slots__ = ()
    WORKFLOW_CLASS_FIELD_NUMBER: _ClassVar[int]
    CURRENT_VERSION_SET_FIELD_NUMBER: _ClassVar[int]
    DESCRIBE_OUTPUT_FIELD_NUMBER: _ClassVar[int]
    workflow_class: str
    current_version_set: bool
    describe_output: str
    def __init__(self, workflow_class: _Optional[str] = ..., current_version_set: _Optional[bool] = ..., describe_output: _Optional[str] = ...) -> None: ...

class OmsVersionRow(_message.Message):
    __slots__ = ()
    OMS_VERSION_FIELD_NUMBER: _ClassVar[int]
    APPS_VERSION_FIELD_NUMBER: _ClassVar[int]
    PROCESSING_VERSION_FIELD_NUMBER: _ClassVar[int]
    FULFILLMENT_VERSION_FIELD_NUMBER: _ClassVar[int]
    DESCRIPTION_FIELD_NUMBER: _ClassVar[int]
    FUTURE_FIELD_NUMBER: _ClassVar[int]
    oms_version: str
    apps_version: str
    processing_version: str
    fulfillment_version: str
    description: str
    future: bool
    def __init__(self, oms_version: _Optional[str] = ..., apps_version: _Optional[str] = ..., processing_version: _Optional[str] = ..., fulfillment_version: _Optional[str] = ..., description: _Optional[str] = ..., future: _Optional[bool] = ...) -> None: ...

class ListOmsVersionsResponse(_message.Message):
    __slots__ = ()
    ROWS_FIELD_NUMBER: _ClassVar[int]
    rows: _containers.RepeatedCompositeFieldContainer[OmsVersionRow]
    def __init__(self, rows: _Optional[_Iterable[_Union[OmsVersionRow, _Mapping]]] = ...) -> None: ...

class StartOmsVersionRolloutRequest(_message.Message):
    __slots__ = ()
    ROLLOUT_ID_FIELD_NUMBER: _ClassVar[int]
    OMS_VERSION_FIELD_NUMBER: _ClassVar[int]
    rollout_id: str
    oms_version: str
    def __init__(self, rollout_id: _Optional[str] = ..., oms_version: _Optional[str] = ...) -> None: ...

class OmsVersionRolloutStep(_message.Message):
    __slots__ = ()
    BOUNDED_CONTEXT_FIELD_NUMBER: _ClassVar[int]
    TARGET_VERSION_FIELD_NUMBER: _ClassVar[int]
    STATUS_FIELD_NUMBER: _ClassVar[int]
    ERROR_MESSAGE_FIELD_NUMBER: _ClassVar[int]
    RESULT_FIELD_NUMBER: _ClassVar[int]
    bounded_context: str
    target_version: str
    status: RolloutStepStatus
    error_message: str
    result: DeployWorkerVersionResponse
    def __init__(self, bounded_context: _Optional[str] = ..., target_version: _Optional[str] = ..., status: _Optional[_Union[RolloutStepStatus, str]] = ..., error_message: _Optional[str] = ..., result: _Optional[_Union[DeployWorkerVersionResponse, _Mapping]] = ...) -> None: ...

class OmsVersionRolloutState(_message.Message):
    __slots__ = ()
    ROLLOUT_ID_FIELD_NUMBER: _ClassVar[int]
    OMS_VERSION_FIELD_NUMBER: _ClassVar[int]
    OVERALL_STATUS_FIELD_NUMBER: _ClassVar[int]
    STEPS_FIELD_NUMBER: _ClassVar[int]
    ERROR_MESSAGE_FIELD_NUMBER: _ClassVar[int]
    rollout_id: str
    oms_version: str
    overall_status: RolloutStepStatus
    steps: _containers.RepeatedCompositeFieldContainer[OmsVersionRolloutStep]
    error_message: str
    def __init__(self, rollout_id: _Optional[str] = ..., oms_version: _Optional[str] = ..., overall_status: _Optional[_Union[RolloutStepStatus, str]] = ..., steps: _Optional[_Iterable[_Union[OmsVersionRolloutStep, _Mapping]]] = ..., error_message: _Optional[str] = ...) -> None: ...

class LoadGenerationState(_message.Message):
    __slots__ = ()
    class ExecutionStatus(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
        __slots__ = ()
        EXECUTION_STATUS_UNSPECIFIED: _ClassVar[LoadGenerationState.ExecutionStatus]
        RUNNING: _ClassVar[LoadGenerationState.ExecutionStatus]
        COMPLETED: _ClassVar[LoadGenerationState.ExecutionStatus]
        CANCELED: _ClassVar[LoadGenerationState.ExecutionStatus]
        FAILED: _ClassVar[LoadGenerationState.ExecutionStatus]
    EXECUTION_STATUS_UNSPECIFIED: LoadGenerationState.ExecutionStatus
    RUNNING: LoadGenerationState.ExecutionStatus
    COMPLETED: LoadGenerationState.ExecutionStatus
    CANCELED: LoadGenerationState.ExecutionStatus
    FAILED: LoadGenerationState.ExecutionStatus
    ENABLEMENT_ID_FIELD_NUMBER: _ClassVar[int]
    STATUS_FIELD_NUMBER: _ClassVar[int]
    ORDERS_SUBMITTED_COUNT_FIELD_NUMBER: _ClassVar[int]
    enablement_id: str
    status: LoadGenerationState.ExecutionStatus
    orders_submitted_count: int
    def __init__(self, enablement_id: _Optional[str] = ..., status: _Optional[_Union[LoadGenerationState.ExecutionStatus, str]] = ..., orders_submitted_count: _Optional[int] = ...) -> None: ...
