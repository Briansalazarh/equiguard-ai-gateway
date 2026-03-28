package com.equiguard.models;

/**
 * Enterprise-grade invariant record representing the HTTP Request Payload.
 */
public record EquiGuardRequest(
        String requestId,
        String content) {
    public EquiGuardRequest {
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("requestId cannot be null or empty");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content cannot be null or empty");
        }
    }
}
