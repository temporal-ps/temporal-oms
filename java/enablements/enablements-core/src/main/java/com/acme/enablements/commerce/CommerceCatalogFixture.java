package com.acme.enablements.commerce;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CommerceCatalogFixture(List<Item> items) {

    public CommerceCatalogFixture {
        items = items == null ? List.of() : List.copyOf(items);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(
            @JsonProperty("item_id") String itemId,
            String name,
            String description,
            @JsonProperty("price_cents") long priceCents,
            @JsonProperty("image_url") String imageUrl,
            @JsonProperty("initial_stock") int initialStock) {
    }
}
