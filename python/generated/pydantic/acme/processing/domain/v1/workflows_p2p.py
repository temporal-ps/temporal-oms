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
import typing


class ProcessOrderRequestExecutionOptions(BaseModel):
    processing_timeout_secs: typing.Optional[int] = Field(default=0)
    oms_properties: typing.Optional[OmsProperties] = Field(default_factory=OmsProperties)
# WORKSHOP: the send_fulfillment field is added to allow callers
# to forward processing.Order data downstream to Fulfillment.
# DO NOT DUPLICATE `send_fulfillment` FIELD if it is already present.
    send_fulfillment: typing.Optional[bool] = Field(default=False)

class ProcessOrderRequest(BaseModel):
    """
     processing.Order
    """

    timestamp: datetime = Field(default_factory=datetime.now)
    options: typing.Optional[ProcessOrderRequestExecutionOptions] = Field(default_factory=ProcessOrderRequestExecutionOptions)
    order_id: str = Field(default="")
    customer_id: str = Field(default="")
    order: Order = Field(default_factory=Order)
    payment: Payment = Field(default_factory=Payment)

class ValidateOrderResponse(BaseModel):
    order: Order = Field(default_factory=Order)
    manual_correction_needed: bool = Field(default=False)
    support_ticket_id: str = Field(default="")
    validation_failures: typing.List[str] = Field(default_factory=list)

class EnrichedItem(BaseModel):
    item_id: str = Field(default="")
    sku_id: str = Field(default="")
    brand_code: str = Field(default="")
    quantity: int = Field(default=0)

class EnrichOrderResponse(BaseModel):
    order: Order = Field(default_factory=Order)
    items: typing.List[EnrichedItem] = Field(default_factory=list)

class FulfillOrderResponse(BaseModel):
    pass

class GetProcessOrderStateResponse(BaseModel):
    args: typing.List[ProcessOrderRequest] = Field(default_factory=list)
    validation: ValidateOrderResponse = Field(default_factory=ValidateOrderResponse)
    enrichment: EnrichOrderResponse = Field(default_factory=EnrichOrderResponse)
    fulfillment: FulfillOrderResponse = Field(default_factory=FulfillOrderResponse)
    errors: typing.List[str] = Field(default_factory=list)

class EnrichOrderRequest(BaseModel):
    order: Order = Field(default_factory=Order)

class ValidateOrderRequest(BaseModel):
    validation_timeout_secs: int = Field(default=0)
    customer_id: str = Field(default="")
    order: Order = Field(default_factory=Order)

class CompletePaymentRequest(BaseModel):
    """
     Update: completePayment
    """

    rrn: str = Field(default="")
    amount_cents: int = Field(default=0)

class CompletePaymentResponse(BaseModel):
    success: bool = Field(default=False)
    message: str = Field(default="")

class ValidatePaymentRequest(BaseModel):
    rrn: str = Field(default="")
    expected_amount_cents: int = Field(default=0)

class ValidatePaymentResponse(BaseModel):
    valid: bool = Field(default=False)
    payment_status: str = Field(default="")
    actual_amount_cents: int = Field(default=0)

class FulfillOrderRequest(BaseModel):
    customer_id: str = Field(default="")
    order: Order = Field(default_factory=Order)
    items: typing.List[EnrichedItem] = Field(default_factory=list)

class ManuallyValidateOrderRequest(BaseModel):
    customer_id: str = Field(default="")
    order: Order = Field(default_factory=Order)
    workflow_id: str = Field(default="")
    activity_id: str = Field(default="")

class InitializeSupportTeam(BaseModel):
    validation_requests: typing.List[ManuallyValidateOrderRequest] = Field(default_factory=list)

class ManuallyValidateOrderResponse(BaseModel):
    pass

class CompleteOrderValidationRequest(BaseModel):
    validation_request: ManuallyValidateOrderRequest = Field(default_factory=ManuallyValidateOrderRequest)
    validation_response: ValidateOrderResponse = Field(default_factory=ValidateOrderResponse)

class GetSupportTeamStateResponse(BaseModel):
    validation_requests: typing.List[ManuallyValidateOrderRequest] = Field(default_factory=list)
