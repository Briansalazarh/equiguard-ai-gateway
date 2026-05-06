package com.equiguard.models;

/**
 * Enterprise-grade invariant record representing the HTTP Request Payload.
 * The {@code language} field is optional (BCP-47 tag, e.g. "es", "en").
 * When omitted it defaults to {@value #DEFAULT_LANGUAGE}.
 */
public record EquiGuardRequest(
        String requestId,
        String content,
        String language) {

    /** Maximum allowed content length enforced by Azure AI Language service. */
    public static final int MAX_CONTENT_LENGTH = 5120;

    /** Default language when none is provided by the caller. */
    public static final String DEFAULT_LANGUAGE = "es";

    public EquiGuardRequest {
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("requestId cannot be null or empty");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content cannot be null or empty");
        }
        if (content.length() > MAX_CONTENT_LENGTH) {
            throw new IllegalArgumentException(
                    "content exceeds the maximum allowed length of " + MAX_CONTENT_LENGTH + " characters");
        }
        language = (language == null || language.isBlank()) ? DEFAULT_LANGUAGE : language.trim().toLowerCase();
    }
}
