package com.acme.enablements.integrations;

import com.acme.enablements.converters.ProtobufJsonHttpMessageConverter;
import com.acme.proto.acme.common.v1.Address;
import com.acme.proto.acme.common.v1.EasyPostAddress;
import com.acme.proto.acme.common.v1.EasyPostRate;
import com.acme.proto.acme.common.v1.EasyPostShipment;
import com.acme.proto.acme.common.v1.Money;
import com.acme.proto.acme.common.v1.Shipment;
import com.acme.proto.acme.fulfillment.domain.fulfillment.v1.FindAlternateWarehouseRequest;
import com.acme.proto.acme.fulfillment.domain.fulfillment.v1.GetLocationEventsRequest;
import com.acme.proto.acme.fulfillment.domain.fulfillment.v1.GetShippingRatesRequest;
import com.acme.proto.acme.fulfillment.domain.fulfillment.v1.HoldItemsRequest;
import com.acme.proto.acme.fulfillment.domain.fulfillment.v1.PrintShippingLabelRequest;
import com.acme.proto.acme.fulfillment.domain.fulfillment.v1.RiskLevel;
import com.acme.proto.acme.fulfillment.domain.fulfillment.v1.ShippingLineItem;
import com.acme.proto.acme.fulfillment.domain.fulfillment.v1.VerifyAddressRequest;
import com.acme.proto.acme.oms.v1.Order;
import com.acme.proto.acme.processing.domain.processing.v1.EnrichOrderRequest;
import com.acme.proto.acme.processing.domain.processing.v1.ValidateOrderRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Message;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.http.MockHttpOutputMessage;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IntegrationServicesTest {

    private final ShippingFixtureService shipping = new ShippingFixtureService(
            "classpath:/fixtures/shipping-fixtures.json",
            new DefaultResourceLoader(),
            new ObjectMapper());
    private final InventoryIntegrationService inventory = new InventoryIntegrationService(shipping);

    @Test
    void commerceInvalidOrderRequiresManualCorrection() {
        var service = new CommerceIntegrationService();
        var response = service.validateOrder(ValidateOrderRequest.newBuilder()
                .setOrder(Order.newBuilder().setOrderId("invalid-order-1").build())
                .build());

        assertThat(response.getManualCorrectionNeeded()).isTrue();
    }

    @Test
    void pimsMapsKnownItemsAndPassesThroughUnknownItemIdsAsSkuIds() {
        var service = new PimsIntegrationService();
        var response = service.enrichOrder(EnrichOrderRequest.newBuilder()
                .setOrder(Order.newBuilder()
                        .setOrderId("order-1")
                        .addItems(com.acme.proto.acme.oms.v1.Item.newBuilder()
                                .setItemId("ITEM-ELEC-001")
                                .setQuantity(2)
                                .build())
                        .addItems(com.acme.proto.acme.oms.v1.Item.newBuilder()
                                .setItemId("unknown")
                                .setQuantity(1)
                                .build())
                        .build())
                .build());

        assertThat(response.getItems(0).getSkuId()).isEqualTo("ELEC-SKU-001");
        assertThat(response.getItems(0).getBrandCode()).isEqualTo("NEXGEN");
        assertThat(response.getItems(0).getQuantity()).isEqualTo(2);
        assertThat(response.getItems(1).getSkuId()).isEqualTo("unknown");
        assertThat(response.getItems(1).getBrandCode()).isEqualTo("GENERIC");
    }

    @Test
    void inventoryResolvesPrimaryAndAlternateWarehouses() {
        var lookup = inventory.lookupInventoryAddress(com.acme.proto.acme.fulfillment.domain.fulfillment.v1.LookupInventoryAddressRequest.newBuilder()
                .addItems(ShippingLineItem.newBuilder().setSkuId("ELEC-SKU-001").setQuantity(1).build())
                .build());

        assertThat(lookup.getAddress().getEasypost().getId()).isEqualTo("adr_wh_east_01");

        var alternate = inventory.findAlternateWarehouse(FindAlternateWarehouseRequest.newBuilder()
                .addItems(ShippingLineItem.newBuilder().setSkuId("ELEC-SKU-001").setQuantity(1).build())
                .setCurrentAddressId("adr_wh_east_01")
                .build());

        assertThat(alternate.getAddress().getEasypost().getId()).isEqualTo("adr_wh_east_02");
    }

    @Test
    void inventoryLifecycleReturnsStableStubIds() {
        assertThat(inventory.holdItems(HoldItemsRequest.newBuilder()
                .setOrderId("order-1")
                .build()).getHoldId()).isEqualTo("hold_stub_order-1");
    }

    @Test
    void shippingVerifiesAddressesAndReturnsAllRates() {
        var verified = shipping.verifyAddress(VerifyAddressRequest.newBuilder()
                .setCustomerId("customer-1")
                .setAddress(Address.newBuilder()
                        .setEasypost(EasyPostAddress.newBuilder()
                                .setStreet1("11 Wall St")
                                .setCity("New York")
                                .setState("NY")
                                .setZip("10005")
                                .setCountry("US")
                                .build())
                        .build())
                .build());

        assertThat(verified.getAddress().getEasypost().getId()).isEqualTo("adr_dest_nyc_01");

        var rates = shipping.getShippingRates(GetShippingRatesRequest.newBuilder()
                .setFromEasypostId("adr_wh_east_01")
                .setToEasypostId("adr_dest_nyc_01")
                .addItems(ShippingLineItem.newBuilder().setSkuId("ELEC-SKU-001").setQuantity(1).build())
                .build());

        assertThat(rates.getShipmentId()).isEqualTo("shp_adr_wh_east_01_to_adr_dest_nyc_01");
        assertThat(rates.getOptionsList())
                .extracting(option -> option.getRateId())
                .containsExactly("rate_wh_east_01_nyc_ground", "rate_wh_east_01_nyc_2day", "rate_wh_east_01_nyc_priority");
        assertThat(rates.getOptionsList())
                .allSatisfy(option -> assertThat(option.getCost().getUnits()).isLessThanOrEqualTo(1000));
    }

    @Test
    void shippingRatesIncludeWarehouseToWarehouseScenarioRoute() {
        var rates = shipping.getShippingRates(GetShippingRatesRequest.newBuilder()
                .setFromEasypostId("adr_wh_east_01")
                .setToEasypostId("adr_wh_west_01")
                .addItems(ShippingLineItem.newBuilder().setSkuId("ELEC-SKU-001").setQuantity(1).build())
                .build());

        assertThat(rates.getShipmentId()).isEqualTo("shp_adr_wh_east_01_to_adr_wh_west_01");
        assertThat(rates.getOptionsList())
                .extracting(option -> option.getRateId())
                .containsExactly(
                        "rate_wh_east_01_wh_west_01_2day",
                        "rate_wh_east_01_wh_west_01_priority",
                        "rate_wh_east_01_wh_west_01_ground");
    }

    @Test
    void shippingRatesIncludeAlternateWarehouseScenarioRoutes() {
        var nycRates = shipping.getShippingRates(GetShippingRatesRequest.newBuilder()
                .setFromEasypostId("adr_wh_east_02")
                .setToEasypostId("adr_dest_nyc_01")
                .addItems(ShippingLineItem.newBuilder().setSkuId("ELEC-SKU-001").setQuantity(1).build())
                .build());

        assertThat(nycRates.getOptionsList())
                .allSatisfy(option -> assertThat(option.getCost().getUnits()).isLessThanOrEqualTo(1000));

        var westRates = shipping.getShippingRates(GetShippingRatesRequest.newBuilder()
                .setFromEasypostId("adr_wh_east_02")
                .setToEasypostId("adr_wh_west_01")
                .addItems(ShippingLineItem.newBuilder().setSkuId("ELEC-SKU-001").setQuantity(1).build())
                .build());

        assertThat(westRates.getShipmentId()).isEqualTo("shp_adr_wh_east_02_to_adr_wh_west_01");
        assertThat(westRates.getOptionsList())
                .allSatisfy(option -> assertThat(option.getCost().getUnits()).isGreaterThan(1000));
    }

    @Test
    void shippingScenariosMutateCopiedRatesDeterministically() {
        var marginSpike = shipping.getShippingRates(baseRatesRequest()
                .setSelectedShipment(Shipment.newBuilder()
                        .setPaidPrice(Money.newBuilder().setCurrency("USD").setUnits(1).build())
                        .build())
                .build());

        assertThat(marginSpike.getOptionsList())
                .allSatisfy(option -> assertThat(option.getCost().getUnits()).isGreaterThan(1));

        var slaBreach = shipping.getShippingRates(baseRatesRequest()
                .setSelectedShipment(Shipment.newBuilder()
                        .setEasypost(EasyPostShipment.newBuilder()
                                .setSelectedRate(EasyPostRate.newBuilder()
                                        .setDeliveryDays(0)
                                        .build())
                                .build())
                        .build())
                .build());

        assertThat(slaBreach.getOptionsList())
                .allSatisfy(option -> assertThat(option.getEstimatedDays()).isGreaterThan(0));
    }

    @Test
    void shippingRatesCanBeListedBySelectedShipmentId() {
        var response = shipping.getShippingRates(GetShippingRatesRequest.newBuilder()
                .setSelectedShipment(Shipment.newBuilder()
                        .setEasypost(EasyPostShipment.newBuilder()
                                .setShipmentId("shp_adr_wh_east_01_to_adr_dest_nyc_01")
                                .build())
                        .build())
                .build());

        assertThat(response.getOptionsCount()).isEqualTo(3);
        assertThat(response.getShipmentId()).isEqualTo("shp_adr_wh_east_01_to_adr_dest_nyc_01");
    }

    @Test
    void printShippingLabelIsSyntheticAndDeterministic() {
        var request = PrintShippingLabelRequest.newBuilder()
                .setOrderId("order-1")
                .setShipmentId("shp_adr_wh_east_01_to_adr_dest_nyc_01")
                .setRateId("rate_wh_east_01_nyc_ground")
                .build();

        var first = shipping.printShippingLabel(request);
        var second = shipping.printShippingLabel(request);

        assertThat(first).isEqualTo(second);
        assertThat(first.getTrackingNumber()).startsWith("1ZFIXTURE");
        assertThat(first.getLabelUrl()).contains(request.getShipmentId()).contains(request.getRateId());
    }

    @Test
    void locationEventsReturnNoRiskAndEchoWindow() {
        var service = new LocationEventsIntegrationService(shipping);
        var request = GetLocationEventsRequest.newBuilder()
                .setCoordinate(shipping.addressById("adr_dest_nyc_01").orElseThrow().getEasypost().getCoordinate())
                .setWithinKm(50.0)
                .setTimezone("America/New_York")
                .build();

        var response = service.getLocationEvents(request);

        assertThat(response.getSummary().getOverallRiskLevel()).isEqualTo(RiskLevel.RISK_LEVEL_NONE);
        assertThat(response.getEventsList()).isEmpty();
        assertThat(response.getTimezone()).isEqualTo("America/New_York");
    }

    @Test
    void protobufConverterPreservesExplicitZeroValues() throws Exception {
        var converter = new ProtobufJsonHttpMessageConverter();
        var input = new MockHttpInputMessage("""
                {
                  "selectedShipment": {
                    "easypost": {
                      "selectedRate": {
                        "deliveryDays": "0"
                      }
                    }
                  }
                }
                """.getBytes(StandardCharsets.UTF_8));

        Message parsed = converter.read(GetShippingRatesRequest.class, input);

        var request = (GetShippingRatesRequest) parsed;
        assertThat(request.getSelectedShipment().getEasypost().getSelectedRate().hasDeliveryDays()).isTrue();
        assertThat(request.getSelectedShipment().getEasypost().getSelectedRate().getDeliveryDays()).isZero();

        var output = new MockHttpOutputMessage();
        converter.write(request, null, output);
        assertThat(output.getBodyAsString()).contains("\"deliveryDays\": \"0\"");
    }

    @Test
    void unknownAddressFailsDeterministically() {
        assertThatThrownBy(() -> shipping.verifyAddress(VerifyAddressRequest.newBuilder()
                .setAddress(Address.newBuilder()
                        .setEasypost(EasyPostAddress.newBuilder()
                                .setStreet1("404 Missing Ave")
                                .setCity("Nowhere")
                                .setState("ZZ")
                                .setZip("00000")
                                .setCountry("US")
                                .build())
                        .build())
                .build()))
                .isInstanceOf(IntegrationFixtureException.class)
                .extracting("code")
                .isEqualTo(IntegrationFixtureException.Code.ADDRESS_VERIFY_FAILED);
    }

    private static GetShippingRatesRequest.Builder baseRatesRequest() {
        return GetShippingRatesRequest.newBuilder()
                .setFromEasypostId("adr_wh_east_01")
                .setToEasypostId("adr_dest_nyc_01")
                .addItems(ShippingLineItem.newBuilder()
                        .setSkuId("ELEC-SKU-001")
                        .setQuantity(1)
                        .build());
    }
}
