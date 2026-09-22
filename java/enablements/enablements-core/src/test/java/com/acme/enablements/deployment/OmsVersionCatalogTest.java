package com.acme.enablements.deployment;

import com.acme.proto.acme.enablements.v1.OmsVersionRow;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards against catalog/spec.md drift: these rows must match spec.md's OMS version
 * table verbatim.
 */
class OmsVersionCatalogTest {

    @Test
    void matchesSpecMdOmsVersionTable() {
        assertThat(OmsVersionCatalog.rows())
                .extracting(OmsVersionRow::getOmsVersion, OmsVersionRow::getAppsVersion,
                        OmsVersionRow::getProcessingVersion, OmsVersionRow::getFulfillmentVersion,
                        OmsVersionRow::getFuture)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("v1", "v1", "v1", OmsVersionCatalog.FULFILLMENT_EMBEDDED, false),
                        org.assertj.core.groups.Tuple.tuple("v2", "v2", "v2", OmsVersionCatalog.FULFILLMENT_EMBEDDED, false),
                        org.assertj.core.groups.Tuple.tuple("v3", "v2", "v3", OmsVersionCatalog.FULFILLMENT_EMBEDDED, false),
                        org.assertj.core.groups.Tuple.tuple("v4", "v3", "v3", "v1", false),
                        org.assertj.core.groups.Tuple.tuple("v5", "v3", "v3", "v2", true),
                        org.assertj.core.groups.Tuple.tuple("v6", "v3", "v4", "v2", true));
    }

    @Test
    void findReturnsEmptyForUnknownVersion() {
        assertThat(OmsVersionCatalog.find("v99")).isEmpty();
    }

    @Test
    void findReturnsMatchingRow() {
        assertThat(OmsVersionCatalog.find("v4"))
                .get()
                .extracting(OmsVersionRow::getAppsVersion)
                .isEqualTo("v3");
    }
}
