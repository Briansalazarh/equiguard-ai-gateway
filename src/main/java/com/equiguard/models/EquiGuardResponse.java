package com.equiguard.models;

import java.util.List;

/**
 * The consolidated audit document that is returned to the user and persisted in
 * Cosmos DB.
 */
public record EquiGuardResponse(
        String id,
        String originalContent,
        String maskedContent,
        List<PiiEntityModel> piiEntities,
        int ethicalScore,
        boolean isSafe,
        String timestamp) {
}
