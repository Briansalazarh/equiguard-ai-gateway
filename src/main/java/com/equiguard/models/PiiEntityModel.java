package com.equiguard.models;

/**
 * Immutable DTO representing an extracted Personally Identifiable Information
 * entity.
 */
public record PiiEntityModel(
        String text,
        String category,
        String subcategory,
        double confidenceScore,
        int offset,
        int length) {
}
