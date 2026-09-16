# This is an automatically generated file, please do not change
# gen by protobuf_to_pydantic[v0.3.3.1](https://github.com/so1n/protobuf_to_pydantic)
# Protobuf Version: 6.33.6 
# Pydantic Version: 2.13.0 
from ....common.v1.values_p2p import Address
from ....common.v1.values_p2p import Money
from ....common.v1.values_p2p import Shipment
from ....oms.v1.values_p2p import Item
from datetime import datetime
from enum import IntEnum
from google.protobuf.message import Message  # type: ignore
from pydantic import BaseModel
from pydantic import ConfigDict
from pydantic import Field
import typing

class DemoScenario(IntEnum):
    NORMAL = 0
    PAYMENT_BEFORE_COMMERCE = 1
    MISSING_COMMERCE_EVENT = 2
    MISSING_PAYMENT_EVENT = 3


class BusinessScenario(IntEnum):
    """
     Business-logic conditions mirroring scripts/scenarios/*, selectable through the
 Commerce App simulator (checkout UI, load generator) instead of only via the
 standalone shell scripts.
    """
    BUSINESS_SCENARIO_NORMAL = 0
    BUSINESS_SCENARIO_MARGIN_SPIKE = 1
    BUSINESS_SCENARIO_SLA_BREACH = 2
    BUSINESS_SCENARIO_INVALID_ORDER = 3

class ScenarioOptions(BaseModel):
    model_config = ConfigDict(validate_default=True)
    scenario: DemoScenario = Field(default=0)

class CommerceOrderState(BaseModel):
    order_id: str = Field(default="")
    customer_id: str = Field(default="")
    items: typing.List[Item] = Field(default_factory=list)
    shipping_address: Address = Field(default_factory=Address)
    selected_shipment: typing.Optional[Shipment] = Field(default_factory=Shipment)
    status: str = Field(default="")# PLACED
    placed_at: datetime = Field(default_factory=datetime.now)
    scenario_options: ScenarioOptions = Field(default_factory=ScenarioOptions)

class CreateCommerceOrderRequest(BaseModel):
    customer_id: str = Field(default="")
    items: typing.List[Item] = Field(default_factory=list)
    shipping_address: Address = Field(default_factory=Address)
    selected_shipment: typing.Optional[Shipment] = Field(default_factory=Shipment)
    scenario_options: ScenarioOptions = Field(default_factory=ScenarioOptions)# from the Svelte checkout's floating scenario selector
# When true, the generated order ID contains "invalid" so the legacy processing
# validation logic (string-matches on order ID) forces a validation failure.
# Order ID is otherwise always server-generated; this is the only way to influence it.
    force_invalid_order_id: bool = Field(default=False)

class CommerceInventoryState(BaseModel):
    stock_by_item_id: "typing.Dict[str, int]" = Field(default_factory=dict)

class HoldInventoryRequest(BaseModel):
    order_id: str = Field(default="")
    items: typing.List[Item] = Field(default_factory=list)

class CommerceCatalogItem(BaseModel):
    item_id: str = Field(default="")
    name: str = Field(default="")
    description: str = Field(default="")
    price_cents: int = Field(default=0)
    image_url: str = Field(default="")
    available_stock: int = Field(default=0)

class GetCommerceCatalogResponse(BaseModel):
    items: typing.List[CommerceCatalogItem] = Field(default_factory=list)

class CommerceShippingRateOption(BaseModel):
    """
     Commerce-App-owned shipping rate quote, distinct from and unrelated to
 enablements' fulfillment-side shipping fixtures.
    """

    rate_id: str = Field(default="")
    carrier: str = Field(default="")
    service_level: str = Field(default="")
    cost: Money = Field(default_factory=Money)
    estimated_days: int = Field(default=0)

class GetCommerceShippingRatesRequest(BaseModel):
    shipping_address: Address = Field(default_factory=Address)
    items: typing.List[Item] = Field(default_factory=list)

class GetCommerceShippingRatesResponse(BaseModel):
    options: typing.List[CommerceShippingRateOption] = Field(default_factory=list)
