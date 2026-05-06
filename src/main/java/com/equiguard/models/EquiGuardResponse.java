package com.equiguard.models;

import java.util.List;

/**
 * The consolidated audit document that is returned to the caller and persisted
 * in Cosmos DB.
 * <p>
 * {@code auditStatus} values: {@code "OK"}, {@code "BLOCKED"} (unsafe content
 * detected), or {@code "DEGRADED"} (ethics audit unavailable at processing
 * time).
 */
public record EquiGuardResponse(
        String id,
        String originalContent,
        String maskedContent,
        List<PiiEntityModel> piiEntities,
        int ethicalScore,
        boolean isSafe,
        String timestamp,
        String auditStatus) {
}
