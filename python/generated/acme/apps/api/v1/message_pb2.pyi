import datetime

from google.protobuf import timestamp_pb2 as _timestamp_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class SubmitOrderResponse(_message.Message):
    __slots__ = ()
    ORDER_ID_FIELD_NUMBER: _ClassVar[int]
    STATUS_FIELD_NUMBER: _ClassVar[int]
    CREATED_AT_FIELD_NUMBER: _ClassVar[int]
    order_id: str
    status: str
    created_at: _timestamp_pb2.Timestamp
    def __init__(self, order_id: _Optional[str] = ..., status: _Optional[str] = ..., created_at: _Optional[_Union[datetime.datetime, _timestamp_pb2.Timestamp, _Mapping]] = ...) -> None: ...

class MakePaymentResponse(_message.Message):
    __slots__ = ()
    ORDER_ID_FIELD_NUMBER: _ClassVar[int]
    STATUS_FIELD_NUMBER: _ClassVar[int]
    PROCESSED_AT_FIELD_NUMBER: _ClassVar[int]
    order_id: str
    status: str
    processed_at: _timestamp_pb2.Timestamp
    def __init__(self, order_id: _Optional[str] = ..., status: _Optional[str] = ..., processed_at: _Optional[_Union[datetime.datetime, _timestamp_pb2.Timestamp, _Mapping]] = ...) -> None: ...

class ListOrdersRequest(_message.Message):
    __slots__ = ()
    CUSTOMER_ID_FIELD_NUMBER: _ClassVar[int]
    PAGE_SIZE_FIELD_NUMBER: _ClassVar[int]
    PAGE_TOKEN_FIELD_NUMBER: _ClassVar[int]
    customer_id: str
    page_size: int
    page_token: str
    def __init__(self, customer_id: _Optional[str] = ..., page_size: _Optional[int] = ..., page_token: _Optional[str] = ...) -> None: ...

class ListOrdersResponse(_message.Message):
    __slots__ = ()
    ORDERS_FIELD_NUMBER: _ClassVar[int]
    NEXT_PAGE_TOKEN_FIELD_NUMBER: _ClassVar[int]
    orders: _containers.RepeatedCompositeFieldContainer[OrderSummary]
    next_page_token: str
    def __init__(self, orders: _Optional[_Iterable[_Union[OrderSummary, _Mapping]]] = ..., next_page_token: _Optional[str] = ...) -> None: ...

class OrderSummary(_message.Message):
    __slots__ = ()
    ORDER_ID_FIELD_NUMBER: _ClassVar[int]
    CUSTOMER_ID_FIELD_NUMBER: _ClassVar[int]
    STATUS_FIELD_NUMBER: _ClassVar[int]
    TOTAL_AMOUNT_CENTS_FIELD_NUMBER: _ClassVar[int]
    CREATED_AT_FIELD_NUMBER: _ClassVar[int]
    order_id: str
    customer_id: str
    status: str
    total_amount_cents: int
    created_at: _timestamp_pb2.Timestamp
    def __init__(self, order_id: _Optional[str] = ..., customer_id: _Optional[str] = ..., status: _Optional[str] = ..., total_amount_cents: _Optional[int] = ..., created_at: _Optional[_Union[datetime.datetime, _timestamp_pb2.Timestamp, _Mapping]] = ...) -> None: ...

class ListProductsRequest(_message.Message):
    __slots__ = ()
    LIMIT_FIELD_NUMBER: _ClassVar[int]
    limit: int
    def __init__(self, limit: _Optional[int] = ...) -> None: ...

class ListProductsResponse(_message.Message):
    __slots__ = ()
    ITEMS_FIELD_NUMBER: _ClassVar[int]
    items: _containers.RepeatedCompositeFieldContainer[Product]
    def __init__(self, items: _Optional[_Iterable[_Union[Product, _Mapping]]] = ...) -> None: ...

class Product(_message.Message):
    __slots__ = ()
    ITEM_ID_FIELD_NUMBER: _ClassVar[int]
    NAME_FIELD_NUMBER: _ClassVar[int]
    DESCRIPTION_FIELD_NUMBER: _ClassVar[int]
    PRICE_CENTS_FIELD_NUMBER: _ClassVar[int]
    IMAGE_URL_FIELD_NUMBER: _ClassVar[int]
    item_id: str
    name: str
    description: str
    price_cents: int
    image_url: str
    def __init__(self, item_id: _Optional[str] = ..., name: _Optional[str] = ..., description: _Optional[str] = ..., price_cents: _Optional[int] = ..., image_url: _Optional[str] = ...) -> None: ...
