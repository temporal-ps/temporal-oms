package com.acme.enablements.deployment;

import com.acme.proto.acme.enablements.v1.OmsVersionRow;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * spec.md's OMS version table (apps.Order / processing.Order / fulfillment version per
 * OMS version) as data: the single source both OmsVersionRolloutImpl and the
 * GET /api/v1/enablements/oms-versions endpoint read from, instead of a
 * hand-maintained TypeScript copy.
 */
public final class OmsVersionCatalog {

    public static final String FULFILLMENT_EMBEDDED = "embedded";

    private static final List<OmsVersionRow> ROWS = List.of(
            row("v1", "v1", "v1", FULFILLMENT_EMBEDDED,
                    "True baseline, no Worker Versioning, straight Kafka", false),
            row("v2", "v2", "v2", FULFILLMENT_EMBEDDED,
                    "Worker Versioning turned on", false),
            row("v3", "v2", "v3", FULFILLMENT_EMBEDDED,
                    "processing rolls forward first, backward-compatible", false),
            row("v4", "v3", "v3", "v1",
                    "apps takes over fulfillment via Nexus", false),
            row("v5", "v3", "v3", "v2",
                    "fulfillment.Order becomes Worker-Versioned", true),
            row("v6", "v3", "v4", "v2",
                    "Kafka retired entirely from processing", true));

    private static final Map<String, OmsVersionRow> BY_OMS_VERSION =
            ROWS.stream().collect(Collectors.toMap(OmsVersionRow::getOmsVersion, r -> r));

    private OmsVersionCatalog() {
    }

    public static List<OmsVersionRow> rows() {
        return ROWS;
    }

    public static Optional<OmsVersionRow> find(String omsVersion) {
        return Optional.ofNullable(BY_OMS_VERSION.get(omsVersion));
    }

    private static OmsVersionRow row(String omsVersion, String appsVersion, String processingVersion,
                                      String fulfillmentVersion, String description, boolean future) {
        return OmsVersionRow.newBuilder()
                .setOmsVersion(omsVersion)
                .setAppsVersion(appsVersion)
                .setProcessingVersion(processingVersion)
                .setFulfillmentVersion(fulfillmentVersion)
                .setDescription(description)
                .setFuture(future)
                .build();
    }
}
