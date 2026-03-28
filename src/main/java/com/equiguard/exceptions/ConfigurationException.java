package com.equiguard.exceptions;

/**
 * Exception thrown during application startup if required configs are missing.
 */
public class ConfigurationException extends RuntimeException {
    public ConfigurationException(String message) {
        super(message);
    }
}
