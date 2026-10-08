# This is an automatically generated file, please do not change
# gen by protobuf_to_pydantic[v0.3.3.1](https://github.com/so1n/protobuf_to_pydantic)
# Protobuf Version: 6.33.6 
# Pydantic Version: 2.13.0 
from ....oms.v1.message_p2p import OmsProperties
from ....oms.v1.values_p2p import Order
from ....oms.v1.values_p2p import Payment
from datetime import datetime
from google.protobuf.message import Message  # type: ignore
from pydantic import BaseModel
from pydantic import Field
from ....processing.domain.v1.workflows_p2p import GetProcessOrderStateResponse
from ....processing.domain.v1.workflows_p2p import ProcessOrderRequest
import typing


class CompleteOrderRequestExecutionOptions(BaseModel):
    completion_timeout_secs: typing.Optional[int] = Field(default=0)
    processing_timeout_secs: typing.Optional[int] = Field(default=0)
    oms_properties: typing.Optional[OmsProperties] = Field(default_factory=OmsProperties)

class CompleteOrderRequest(BaseModel):
    timestamp: datetime = Field(default_factory=datetime.now)
    options: typing.Optional[CompleteOrderRequestExecutionOptions] = Field(default_factory=CompleteOrderRequestExecutionOptions)
    order_id: str = Field(default="")
    customer_id: str = Field(default="")

class SubmitOrderRequest(BaseModel):
    timestamp: datetime = Field(default_factory=datetime.now)
    order_id: str = Field(default="")
    order: Order = Field(default_factory=Order)
    complete_order_request: typing.Optional[CompleteOrderRequest] = Field(default_factory=CompleteOrderRequest)

class CapturePaymentRequest(BaseModel):
    timestamp: datetime = Field(default_factory=datetime.now)
    order_id: str = Field(default="")
    payment: Payment = Field(default_factory=Payment)
    complete_order_request: typing.Optional[CompleteOrderRequest] = Field(default_factory=CompleteOrderRequest)

class CancelOrderRequest(BaseModel):
    timestamp: datetime = Field(default_factory=datetime.now)
    reason: str = Field(default="")
    cancelled_by: str = Field(default="")

class GetCompleteOrderStateResponse(BaseModel):
    args: CompleteOrderRequest = Field(default_factory=CompleteOrderRequest)
    options: CompleteOrderRequestExecutionOptions = Field(default_factory=CompleteOrderRequestExecutionOptions)
    errors: typing.List[str] = Field(default_factory=list)
    submitted_orders: typing.List[SubmitOrderRequest] = Field(default_factory=list)
    captured_payments: typing.List[CapturePaymentRequest] = Field(default_factory=list)
    cancellation: typing.Optional[CancelOrderRequest] = Field(default_factory=CancelOrderRequest)
    process_order: typing.Optional[ProcessOrderRequest] = Field(default_factory=ProcessOrderRequest)
    processed_order: typing.Optional[GetProcessOrderStateResponse] = Field(default_factory=GetProcessOrderStateResponse)

class GetOptionsRequest(BaseModel):
    timestamp: datetime = Field(default_factory=datetime.now)
    options: typing.Optional[CompleteOrderRequestExecutionOptions] = Field(default_factory=CompleteOrderRequestExecutionOptions)

class CancelOrderResponse(BaseModel):
    pass
