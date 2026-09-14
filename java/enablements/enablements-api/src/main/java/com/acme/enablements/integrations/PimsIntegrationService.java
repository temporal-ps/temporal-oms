package com.acme.enablements.integrations;

import com.acme.proto.acme.processing.domain.processing.v1.EnrichOrderRequest;
import com.acme.proto.acme.processing.domain.processing.v1.EnrichOrderResponse;
import com.acme.proto.acme.processing.domain.processing.v1.EnrichedItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class PimsIntegrationService {

    private static final Logger logger = LoggerFactory.getLogger(PimsIntegrationService.class);

    private static final Map<String, EnrichedItem> ITEM_FIXTURES = Map.of(
            "ITEM-ELEC-001", item("ITEM-ELEC-001", "ELEC-SKU-001", "NEXGEN"),
            "ITEM-ELEC-002", item("ITEM-ELEC-002", "ELEC-SKU-002", "NEXGEN"),
            "ITEM-GADG-001", item("ITEM-GADG-001", "GADG-SKU-001", "GADGETCO"),
            "ITEM-GADG-002", item("ITEM-GADG-002", "GADG-SKU-002", "GADGETCO"),
            "ITEM-TECH-001", item("ITEM-TECH-001", "TECH-SKU-001", "TECHCORE"),
            "ITEM-APRL-001", item("ITEM-APRL-001", "APRL-SKU-001", "STYLEHAUS"),
            "ITEM-HOME-001", item("ITEM-HOME-001", "HOME-SKU-001", "HOMECO"),
            "ITEM-SPRT-001", item("ITEM-SPRT-001", "SPRT-SKU-001", "SPORTMAX"),
            "ITEM-SPRT-002", item("ITEM-SPRT-002", "SPRT-SKU-002", "SPORTMAX"),
            "ITEM-APRL-002", item("ITEM-APRL-002", "APRL-SKU-002", "STYLEHAUS")
    );

    public EnrichOrderResponse enrichOrder(EnrichOrderRequest request) {
        logger.info("enrichOrder orderId={}", request.getOrder().getOrderId());
        List<EnrichedItem> enriched = request.getOrder().getItemsList().stream()
                .map(orderItem -> {
                    EnrichedItem fixture = ITEM_FIXTURES.get(orderItem.getItemId());
                    if (fixture != null) {
                        return fixture.toBuilder().setQuantity(orderItem.getQuantity()).build();
                    }
                    // Commerce catalog item IDs already carry a warehouse-routing category
                    // prefix (APRL-, FOOT-, ACC-, BAG-; see commerce-catalog.json), so the
                    // sku_id used for warehouse/shipment lookup is the item_id itself.
                    return EnrichedItem.newBuilder()
                            .setItemId(orderItem.getItemId())
                            .setSkuId(orderItem.getItemId())
                            .setBrandCode("GENERIC")
                            .setQuantity(orderItem.getQuantity())
                            .build();
                })
                .toList();
        return EnrichOrderResponse.newBuilder()
                .setOrder(request.getOrder())
                .addAllItems(enriched)
                .build();
    }

    private static EnrichedItem item(String itemId, String skuId, String brandCode) {
        return EnrichedItem.newBuilder()
                .setItemId(itemId)
                .setSkuId(skuId)
                .setBrandCode(brandCode)
                .build();
    }
}
