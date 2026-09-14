# This is an automatically generated file, please do not change
# gen by protobuf_to_pydantic[v0.3.3.1](https://github.com/so1n/protobuf_to_pydantic)
# Protobuf Version: 6.33.6 
# Pydantic Version: 2.13.0 
from datetime import datetime
from google.protobuf.message import Message  # type: ignore
from pydantic import BaseModel
from pydantic import Field
import typing


class PaymentChargeState(BaseModel):
    charge_id: str = Field(default="")
    order_id: str = Field(default="")
    customer_id: str = Field(default="")
    amount_cents: int = Field(default=0)
    card_last_four: str = Field(default="")
    status: str = Field(default="")# AUTHORIZED | CAPTURED | VOIDED | CAPTURE_FAILED | DECLINED | INSUFFICIENT_FUNDS | EXPIRED_CARD
    decline_reason: str = Field(default="")
    authorized_at: datetime = Field(default_factory=datetime.now)
    captured_at: typing.Optional[datetime] = Field(default_factory=datetime.now)

class CreateChargeRequest(BaseModel):
    order_id: str = Field(default="")
    customer_id: str = Field(default="")
    amount_cents: int = Field(default=0)
    card_number: str = Field(default="")

class CaptureChargeRequest(BaseModel):
    charge_id: str = Field(default="")

class VoidChargeRequest(BaseModel):
    charge_id: str = Field(default="")
