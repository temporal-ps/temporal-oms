import datetime

from google.protobuf import timestamp_pb2 as _timestamp_pb2
from acme.apps.domain.v1 import values_pb2 as _values_pb2
from acme.oms.v1 import message_pb2 as _message_pb2
from acme.oms.v1 import values_pb2 as _values_pb2_1
from acme.processing.domain.v1 import workflows_pb2 as _workflows_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class CompleteOrderRequest(_message.Message):
    __slots__ = ()
    TIMESTAMP_FIELD_NUMBER: _ClassVar[int]
    OPTIONS_FIELD_NUMBER: _ClassVar[int]
    ORDER_ID_FIELD_NUMBER: _ClassVar[int]
    CUSTOMER_ID_FIELD_NUMBER: _ClassVar[int]
    timestamp: _timestamp_pb2.Timestamp
    options: CompleteOrderRequestExecutionOptions
    order_id: str
    customer_id: str
    def __init__(self, timestamp: _Optional[_Union[datetime.datetime, _timestamp_pb2.Timestamp, _Mapping]] = ..., options: _Optional[_Union[CompleteOrderRequestExecutionOptions, _Mapping]] = ..., order_id: _Optional[str] = ..., customer_id: _Optional[str] = ...) -> None: ...

class CompleteOrderRequestExecutionOptions(_message.Message):
    __slots__ = ()
    COMPLETION_TIMEOUT_SECS_FIELD_NUMBER: _ClassVar[int]
    PROCESSING_TIMEOUT_SECS_FIELD_NUMBER: _ClassVar[int]
    OMS_PROPERTIES_FIELD_NUMBER: _ClassVar[int]
    completion_timeout_secs: int
    processing_timeout_secs: int
    oms_properties: _message_pb2.OmsProperties
    def __init__(self, completion_timeout_secs: _Optional[int] = ..., processing_timeout_secs: _Optional[int] = ..., oms_properties: _Optional[_Union[_message_pb2.OmsProperties, _Mapping]] = ...) -> None: ...

class GetCompleteOrderStateResponse(_message.Message):
    __slots__ = ()
    ARGS_FIELD_NUMBER: _ClassVar[int]
    OPTIONS_FIELD_NUMBER: _ClassVar[int]
    ERRORS_FIELD_NUMBER: _ClassVar[int]
    SUBMITTED_ORDERS_FIELD_NUMBER: _ClassVar[int]
    CAPTURED_PAYMENTS_FIELD_NUMBER: _ClassVar[int]
    CANCELLATION_FIELD_NUMBER: _ClassVar[int]
    PROCESS_ORDER_FIELD_NUMBER: _ClassVar[int]
    PROCESSED_ORDER_FIELD_NUMBER: _ClassVar[int]
    args: CompleteOrderRequest
    options: CompleteOrderRequestExecutionOptions
    errors: _containers.RepeatedScalarFieldContainer[str]
    submitted_orders: _containers.RepeatedCompositeFieldContainer[SubmitOrderRequest]
    captured_payments: _containers.RepeatedCompositeFieldContainer[CapturePaymentRequest]
    cancellation: CancelOrderRequest
    process_order: _workflows_pb2.ProcessOrderRequest
    processed_order: _workflows_pb2.GetProcessOrderStateResponse
    def __init__(self, args: _Optional[_Union[CompleteOrderRequest, _Mapping]] = ..., options: _Optional[_Union[CompleteOrderRequestExecutionOptions, _Mapping]] = ..., errors: _Optional[_Iterable[str]] = ..., submitted_orders: _Optional[_Iterable[_Union[SubmitOrderRequest, _Mapping]]] = ..., captured_payments: _Optional[_Iterable[_Union[CapturePaymentRequest, _Mapping]]] = ..., cancellation: _Optional[_Union[CancelOrderRequest, _Mapping]] = ..., process_order: _Optional[_Union[_workflows_pb2.ProcessOrderRequest, _Mapping]] = ..., processed_order: _Optional[_Union[_workflows_pb2.GetProcessOrderStateResponse, _Mapping]] = ...) -> None: ...

class GetOptionsRequest(_message.Message):
    __slots__ = ()
    TIMESTAMP_FIELD_NUMBER: _ClassVar[int]
    OPTIONS_FIELD_NUMBER: _ClassVar[int]
    timestamp: _timestamp_pb2.Timestamp
    options: CompleteOrderRequestExecutionOptions
    def __init__(self, timestamp: _Optional[_Union[datetime.datetime, _timestamp_pb2.Timestamp, _Mapping]] = ..., options: _Optional[_Union[CompleteOrderRequestExecutionOptions, _Mapping]] = ...) -> None: ...

class CancelOrderRequest(_message.Message):
    __slots__ = ()
    TIMESTAMP_FIELD_NUMBER: _ClassVar[int]
    REASON_FIELD_NUMBER: _ClassVar[int]
    CANCELLED_BY_FIELD_NUMBER: _ClassVar[int]
    timestamp: _timestamp_pb2.Timestamp
    reason: str
    cancelled_by: str
    def __init__(self, timestamp: _Optional[_Union[datetime.datetime, _timestamp_pb2.Timestamp, _Mapping]] = ..., reason: _Optional[str] = ..., cancelled_by: _Optional[str] = ...) -> None: ...

class CancelOrderResponse(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class SubmitOrderRequest(_message.Message):
    __slots__ = ()
    TIMESTAMP_FIELD_NUMBER: _ClassVar[int]
    ORDER_ID_FIELD_NUMBER: _ClassVar[int]
    ORDER_FIELD_NUMBER: _ClassVar[int]
    COMPLETE_ORDER_REQUEST_FIELD_NUMBER: _ClassVar[int]
    timestamp: _timestamp_pb2.Timestamp
    order_id: str
    order: _values_pb2_1.Order
    complete_order_request: CompleteOrderRequest
    def __init__(self, timestamp: _Optional[_Union[datetime.datetime, _timestamp_pb2.Timestamp, _Mapping]] = ..., order_id: _Optional[str] = ..., order: _Optional[_Union[_values_pb2_1.Order, _Mapping]] = ..., complete_order_request: _Optional[_Union[CompleteOrderRequest, _Mapping]] = ...) -> None: ...

class CapturePaymentRequest(_message.Message):
    __slots__ = ()
    TIMESTAMP_FIELD_NUMBER: _ClassVar[int]
    ORDER_ID_FIELD_NUMBER: _ClassVar[int]
    PAYMENT_FIELD_NUMBER: _ClassVar[int]
    COMPLETE_ORDER_REQUEST_FIELD_NUMBER: _ClassVar[int]
    timestamp: _timestamp_pb2.Timestamp
    order_id: str
    payment: _values_pb2_1.Payment
    complete_order_request: CompleteOrderRequest
    def __init__(self, timestamp: _Optional[_Union[datetime.datetime, _timestamp_pb2.Timestamp, _Mapping]] = ..., order_id: _Optional[str] = ..., payment: _Optional[_Union[_values_pb2_1.Payment, _Mapping]] = ..., complete_order_request: _Optional[_Union[CompleteOrderRequest, _Mapping]] = ...) -> None: ...
