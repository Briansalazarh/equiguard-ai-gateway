package com.equiguard.config;

import com.equiguard.exceptions.ConfigurationException;

/**
 * Enterprise-grade environment variable manager ensuring fail-fast
 * initialization.
 */
public class AppConfig {

    private AppConfig() {
    }

    /**
     * Retrieves a required environment variable or fails fast with a clear
     * exception.
     */
    public static String getRequiredEnv(String variableName) {
        String value = System.getenv(variableName);
        if (value == null || value.isBlank()) {
            value = System.getProperty(variableName);
        }
        if (value == null || value.isBlank() || value.startsWith("[YOUR-")) {
            throw new ConfigurationException("CRITICAL: Missing configuration property '" + variableName
                    + "'. Check local.settings.json or System Properties.");
        }
        return value.trim();
    }

    /**
     * Retrieves an optional environment variable, falling back to a default value.
     */
    public static String getOptionalEnv(String variableName, String defaultValue) {
        String value = System.getenv(variableName);
        if (value == null || value.isBlank()) {
            value = System.getProperty(variableName);
        }
        if (value == null || value.isBlank() || value.startsWith("[YOUR-")) {
            return defaultValue;
        }
        return value.trim();
    }
}
