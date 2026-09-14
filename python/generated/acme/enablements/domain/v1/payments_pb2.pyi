import datetime

from google.protobuf import timestamp_pb2 as _timestamp_pb2
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class PaymentChargeState(_message.Message):
    __slots__ = ()
    CHARGE_ID_FIELD_NUMBER: _ClassVar[int]
    ORDER_ID_FIELD_NUMBER: _ClassVar[int]
    CUSTOMER_ID_FIELD_NUMBER: _ClassVar[int]
    AMOUNT_CENTS_FIELD_NUMBER: _ClassVar[int]
    CARD_LAST_FOUR_FIELD_NUMBER: _ClassVar[int]
    STATUS_FIELD_NUMBER: _ClassVar[int]
    DECLINE_REASON_FIELD_NUMBER: _ClassVar[int]
    AUTHORIZED_AT_FIELD_NUMBER: _ClassVar[int]
    CAPTURED_AT_FIELD_NUMBER: _ClassVar[int]
    charge_id: str
    order_id: str
    customer_id: str
    amount_cents: int
    card_last_four: str
    status: str
    decline_reason: str
    authorized_at: _timestamp_pb2.Timestamp
    captured_at: _timestamp_pb2.Timestamp
    def __init__(self, charge_id: _Optional[str] = ..., order_id: _Optional[str] = ..., customer_id: _Optional[str] = ..., amount_cents: _Optional[int] = ..., card_last_four: _Optional[str] = ..., status: _Optional[str] = ..., decline_reason: _Optional[str] = ..., authorized_at: _Optional[_Union[datetime.datetime, _timestamp_pb2.Timestamp, _Mapping]] = ..., captured_at: _Optional[_Union[datetime.datetime, _timestamp_pb2.Timestamp, _Mapping]] = ...) -> None: ...

class CreateChargeRequest(_message.Message):
    __slots__ = ()
    ORDER_ID_FIELD_NUMBER: _ClassVar[int]
    CUSTOMER_ID_FIELD_NUMBER: _ClassVar[int]
    AMOUNT_CENTS_FIELD_NUMBER: _ClassVar[int]
    CARD_NUMBER_FIELD_NUMBER: _ClassVar[int]
    order_id: str
    customer_id: str
    amount_cents: int
    card_number: str
    def __init__(self, order_id: _Optional[str] = ..., customer_id: _Optional[str] = ..., amount_cents: _Optional[int] = ..., card_number: _Optional[str] = ...) -> None: ...

class CaptureChargeRequest(_message.Message):
    __slots__ = ()
    CHARGE_ID_FIELD_NUMBER: _ClassVar[int]
    charge_id: str
    def __init__(self, charge_id: _Optional[str] = ...) -> None: ...

class VoidChargeRequest(_message.Message):
    __slots__ = ()
    CHARGE_ID_FIELD_NUMBER: _ClassVar[int]
    charge_id: str
    def __init__(self, charge_id: _Optional[str] = ...) -> None: ...
