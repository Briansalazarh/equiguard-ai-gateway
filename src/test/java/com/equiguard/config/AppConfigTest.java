package com.equiguard.config;

import com.equiguard.exceptions.ConfigurationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for AppConfig environment-variable resolution logic.
 */
class AppConfigTest {

    private static final String TEST_KEY = "EQUIGUARD_TEST_PROP_" + System.nanoTime();

    @AfterEach
    void cleanup() {
        System.clearProperty(TEST_KEY);
    }

    @Test
    void getRequiredEnv_presentInSystemProperties_returnsValue() {
        System.setProperty(TEST_KEY, "  hello  ");
        assertThat(AppConfig.getRequiredEnv(TEST_KEY)).isEqualTo("hello");
    }

    @Test
    void getRequiredEnv_missing_throwsConfigurationException() {
        assertThatThrownBy(() -> AppConfig.getRequiredEnv(TEST_KEY))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining(TEST_KEY);
    }

    @Test
    void getRequiredEnv_blankValue_throwsConfigurationException() {
        System.setProperty(TEST_KEY, "   ");
        assertThatThrownBy(() -> AppConfig.getRequiredEnv(TEST_KEY))
                .isInstanceOf(ConfigurationException.class);
    }

    @Test
    void getRequiredEnv_placeholderValue_throwsConfigurationException() {
        System.setProperty(TEST_KEY, "[YOUR-KEY]");
        assertThatThrownBy(() -> AppConfig.getRequiredEnv(TEST_KEY))
                .isInstanceOf(ConfigurationException.class);
    }

    @Test
    void getOptionalEnv_missing_returnsDefault() {
        assertThat(AppConfig.getOptionalEnv(TEST_KEY, "default-val")).isEqualTo("default-val");
    }

    @Test
    void getOptionalEnv_present_returnsValue() {
        System.setProperty(TEST_KEY, "real-value");
        assertThat(AppConfig.getOptionalEnv(TEST_KEY, "default-val")).isEqualTo("real-value");
    }

    @Test
    void getOptionalEnv_placeholder_returnsDefault() {
        System.setProperty(TEST_KEY, "[YOUR-PLACEHOLDER]");
        assertThat(AppConfig.getOptionalEnv(TEST_KEY, "fallback")).isEqualTo("fallback");
    }
}
