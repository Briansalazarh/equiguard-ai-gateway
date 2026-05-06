package com.equiguard.models;

/**
 * RFC 7807 Problem Details for HTTP APIs.
 * Provides a standardized machine-readable format for API error responses.
 */
public record ProblemDetail(
        String type,
        String title,
        int status,
        String detail,
        String instance) {

    private static final String BASE_TYPE_URI = "https://equiguard.ai/errors/";

    public static ProblemDetail of(String errorCode, String title, int status, String detail, String instance) {
        return new ProblemDetail(BASE_TYPE_URI + errorCode, title, status, detail, instance);
    }

    public static ProblemDetail badRequest(String detail, String instance) {
        return of("validation-error", "Validation Error", 400, detail, instance);
    }

    public static ProblemDetail internalError(String instance) {
        return of("internal-error", "Internal Server Error", 500, "Security audit processing failed.", instance);
    }

    public static ProblemDetail configurationError(String instance) {
        return of("configuration-error", "Service Configuration Error", 500,
                "The service is misconfigured. Contact the system administrator.", instance);
    }
}
