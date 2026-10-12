package com.acme.enablements.controllers;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeControllerTest {

    @Test
    void reportsKubernetesWhenServiceHostIsSet() {
        assertThat(RuntimeController.detect(Map.of(RuntimeController.KUBERNETES_SERVICE_HOST, "10.96.0.1")).kubernetes())
                .isTrue();
    }

    @Test
    void reportsLocalWhenServiceHostIsMissingOrBlank() {
        assertThat(RuntimeController.detect(Map.of()).kubernetes()).isFalse();
        assertThat(RuntimeController.detect(Map.of(RuntimeController.KUBERNETES_SERVICE_HOST, " ")).kubernetes())
                .isFalse();
    }
}
