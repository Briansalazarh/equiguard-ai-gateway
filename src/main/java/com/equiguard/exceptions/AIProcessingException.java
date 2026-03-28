package com.equiguard.exceptions;

/**
 * Exception wrapping Azure AI SDK errors for consistent HTTP 500 translation
 * without leaking framework details.
 */
public class AIProcessingException extends RuntimeException {
    public AIProcessingException(String message, Throwable cause) {
        super(message, cause);
    }

    public AIProcessingException(String message) {
        super(message);
    }
}
