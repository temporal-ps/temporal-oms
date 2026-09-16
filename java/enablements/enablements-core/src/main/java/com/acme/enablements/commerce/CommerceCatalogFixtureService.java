package com.acme.enablements.commerce;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;

/**
 * Loads the static commerce catalog fixture once at startup, the same
 * pattern ShippingFixtureService uses for warehouse fixtures. Lives in
 * enablements-core (not enablements-api) because enablements-workers also
 * needs it, at startup, to seed CommerceInventory's initial stock.
 */
@Service
public class CommerceCatalogFixtureService {

    private static final String DEFAULT_FIXTURE_PATH = "classpath:/fixtures/commerce-catalog.json";

    private final CommerceCatalogFixture fixture;

    public CommerceCatalogFixtureService(
            @Value("${enablements.commerce.catalog-fixture-path:" + DEFAULT_FIXTURE_PATH + "}") String fixturePath,
            ResourceLoader resourceLoader,
            ObjectMapper objectMapper) {
        this.fixture = loadFixture(fixturePath, resourceLoader, objectMapper);
    }

    public CommerceCatalogFixture fixture() {
        return fixture;
    }

    private static CommerceCatalogFixture loadFixture(String fixturePath, ResourceLoader resourceLoader, ObjectMapper objectMapper) {
        Resource resource = resourceLoader.getResource(fixturePath);
        if (!resource.exists()) {
            throw new IllegalStateException("Commerce catalog fixture does not exist: " + fixturePath);
        }
        try (InputStream input = resource.getInputStream()) {
            return objectMapper.readValue(input, CommerceCatalogFixture.class);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load commerce catalog fixture: " + fixturePath, e);
        }
    }
}
