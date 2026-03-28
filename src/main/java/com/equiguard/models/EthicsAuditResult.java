package com.equiguard.models;

/**
 * Domain record encapsulating the result of an Ethical AI Content Safety Audit.
 */
public record EthicsAuditResult(
        int ethicalScore,
        boolean isSafe,
        String severityDetails) {
}
