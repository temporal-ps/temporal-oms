import datetime

from google.protobuf import timestamp_pb2 as _timestamp_pb2
from acme.oms.v1 import values_pb2 as _values_pb2
from acme.common.v1 import values_pb2 as _values_pb2_1
from google.protobuf.internal import containers as _containers
from google.protobuf.internal import enum_type_wrapper as _enum_type_wrapper
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class DemoScenario(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    NORMAL: _ClassVar[DemoScenario]
    PAYMENT_BEFORE_COMMERCE: _ClassVar[DemoScenario]
    MISSING_COMMERCE_EVENT: _ClassVar[DemoScenario]
    MISSING_PAYMENT_EVENT: _ClassVar[DemoScenario]
NORMAL: DemoScenario
PAYMENT_BEFORE_COMMERCE: DemoScenario
MISSING_COMMERCE_EVENT: DemoScenario
MISSING_PAYMENT_EVENT: DemoScenario

class ScenarioOptions(_message.Message):
    __slots__ = ()
    SCENARIO_FIELD_NUMBER: _ClassVar[int]
    scenario: DemoScenario
    def __init__(self, scenario: _Optional[_Union[DemoScenario, str]] = ...) -> None: ...

class CommerceOrderState(_message.Message):
    __slots__ = ()
    ORDER_ID_FIELD_NUMBER: _ClassVar[int]
    CUSTOMER_ID_FIELD_NUMBER: _ClassVar[int]
    ITEMS_FIELD_NUMBER: _ClassVar[int]
    SHIPPING_ADDRESS_FIELD_NUMBER: _ClassVar[int]
    SELECTED_SHIPMENT_FIELD_NUMBER: _ClassVar[int]
    STATUS_FIELD_NUMBER: _ClassVar[int]
    PLACED_AT_FIELD_NUMBER: _ClassVar[int]
    SCENARIO_OPTIONS_FIELD_NUMBER: _ClassVar[int]
    order_id: str
    customer_id: str
    items: _containers.RepeatedCompositeFieldContainer[_values_pb2.Item]
    shipping_address: _values_pb2_1.Address
    selected_shipment: _values_pb2_1.Shipment
    status: str
    placed_at: _timestamp_pb2.Timestamp
    scenario_options: ScenarioOptions
    def __init__(self, order_id: _Optional[str] = ..., customer_id: _Optional[str] = ..., items: _Optional[_Iterable[_Union[_values_pb2.Item, _Mapping]]] = ..., shipping_address: _Optional[_Union[_values_pb2_1.Address, _Mapping]] = ..., selected_shipment: _Optional[_Union[_values_pb2_1.Shipment, _Mapping]] = ..., status: _Optional[str] = ..., placed_at: _Optional[_Union[datetime.datetime, _timestamp_pb2.Timestamp, _Mapping]] = ..., scenario_options: _Optional[_Union[ScenarioOptions, _Mapping]] = ...) -> None: ...

class CreateCommerceOrderRequest(_message.Message):
    __slots__ = ()
    CUSTOMER_ID_FIELD_NUMBER: _ClassVar[int]
    ITEMS_FIELD_NUMBER: _ClassVar[int]
    SHIPPING_ADDRESS_FIELD_NUMBER: _ClassVar[int]
    SELECTED_SHIPMENT_FIELD_NUMBER: _ClassVar[int]
    SCENARIO_OPTIONS_FIELD_NUMBER: _ClassVar[int]
    customer_id: str
    items: _containers.RepeatedCompositeFieldContainer[_values_pb2.Item]
    shipping_address: _values_pb2_1.Address
    selected_shipment: _values_pb2_1.Shipment
    scenario_options: ScenarioOptions
    def __init__(self, customer_id: _Optional[str] = ..., items: _Optional[_Iterable[_Union[_values_pb2.Item, _Mapping]]] = ..., shipping_address: _Optional[_Union[_values_pb2_1.Address, _Mapping]] = ..., selected_shipment: _Optional[_Union[_values_pb2_1.Shipment, _Mapping]] = ..., scenario_options: _Optional[_Union[ScenarioOptions, _Mapping]] = ...) -> None: ...

class CommerceInventoryState(_message.Message):
    __slots__ = ()
    class StockByItemIdEntry(_message.Message):
        __slots__ = ()
        KEY_FIELD_NUMBER: _ClassVar[int]
        VALUE_FIELD_NUMBER: _ClassVar[int]
        key: str
        value: int
        def __init__(self, key: _Optional[str] = ..., value: _Optional[int] = ...) -> None: ...
    STOCK_BY_ITEM_ID_FIELD_NUMBER: _ClassVar[int]
    stock_by_item_id: _containers.ScalarMap[str, int]
    def __init__(self, stock_by_item_id: _Optional[_Mapping[str, int]] = ...) -> None: ...

class HoldInventoryRequest(_message.Message):
    __slots__ = ()
    ORDER_ID_FIELD_NUMBER: _ClassVar[int]
    ITEMS_FIELD_NUMBER: _ClassVar[int]
    order_id: str
    items: _containers.RepeatedCompositeFieldContainer[_values_pb2.Item]
    def __init__(self, order_id: _Optional[str] = ..., items: _Optional[_Iterable[_Union[_values_pb2.Item, _Mapping]]] = ...) -> None: ...

class CommerceCatalogItem(_message.Message):
    __slots__ = ()
    ITEM_ID_FIELD_NUMBER: _ClassVar[int]
    NAME_FIELD_NUMBER: _ClassVar[int]
    DESCRIPTION_FIELD_NUMBER: _ClassVar[int]
    PRICE_CENTS_FIELD_NUMBER: _ClassVar[int]
    IMAGE_URL_FIELD_NUMBER: _ClassVar[int]
    AVAILABLE_STOCK_FIELD_NUMBER: _ClassVar[int]
    item_id: str
    name: str
    description: str
    price_cents: int
    image_url: str
    available_stock: int
    def __init__(self, item_id: _Optional[str] = ..., name: _Optional[str] = ..., description: _Optional[str] = ..., price_cents: _Optional[int] = ..., image_url: _Optional[str] = ..., available_stock: _Optional[int] = ...) -> None: ...

class GetCommerceCatalogResponse(_message.Message):
    __slots__ = ()
    ITEMS_FIELD_NUMBER: _ClassVar[int]
    items: _containers.RepeatedCompositeFieldContainer[CommerceCatalogItem]
    def __init__(self, items: _Optional[_Iterable[_Union[CommerceCatalogItem, _Mapping]]] = ...) -> None: ...

class CommerceShippingRateOption(_message.Message):
    __slots__ = ()
    RATE_ID_FIELD_NUMBER: _ClassVar[int]
    CARRIER_FIELD_NUMBER: _ClassVar[int]
    SERVICE_LEVEL_FIELD_NUMBER: _ClassVar[int]
    COST_FIELD_NUMBER: _ClassVar[int]
    ESTIMATED_DAYS_FIELD_NUMBER: _ClassVar[int]
    rate_id: str
    carrier: str
    service_level: str
    cost: _values_pb2_1.Money
    estimated_days: int
    def __init__(self, rate_id: _Optional[str] = ..., carrier: _Optional[str] = ..., service_level: _Optional[str] = ..., cost: _Optional[_Union[_values_pb2_1.Money, _Mapping]] = ..., estimated_days: _Optional[int] = ...) -> None: ...

class GetCommerceShippingRatesRequest(_message.Message):
    __slots__ = ()
    SHIPPING_ADDRESS_FIELD_NUMBER: _ClassVar[int]
    ITEMS_FIELD_NUMBER: _ClassVar[int]
    shipping_address: _values_pb2_1.Address
    items: _containers.RepeatedCompositeFieldContainer[_values_pb2.Item]
    def __init__(self, shipping_address: _Optional[_Union[_values_pb2_1.Address, _Mapping]] = ..., items: _Optional[_Iterable[_Union[_values_pb2.Item, _Mapping]]] = ...) -> None: ...

class GetCommerceShippingRatesResponse(_message.Message):
    __slots__ = ()
    OPTIONS_FIELD_NUMBER: _ClassVar[int]
    options: _containers.RepeatedCompositeFieldContainer[CommerceShippingRateOption]
    def __init__(self, options: _Optional[_Iterable[_Union[CommerceShippingRateOption, _Mapping]]] = ...) -> None: ...
