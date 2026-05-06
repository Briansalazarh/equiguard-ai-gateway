package com.equiguard.services;

import com.azure.ai.contentsafety.ContentSafetyClient;
import com.azure.ai.contentsafety.ContentSafetyClientBuilder;
import com.azure.ai.contentsafety.models.AnalyzeTextOptions;
import com.azure.ai.contentsafety.models.AnalyzeTextResult;
import com.azure.ai.contentsafety.models.TextCategoriesAnalysis;
import com.azure.ai.textanalytics.TextAnalyticsClient;
import com.azure.ai.textanalytics.TextAnalyticsClientBuilder;
import com.azure.ai.textanalytics.models.PiiEntity;
import com.azure.ai.textanalytics.models.PiiEntityCollection;
import com.azure.core.credential.AzureKeyCredential;
import com.azure.core.http.policy.ExponentialBackoff;
import com.azure.core.http.policy.RetryPolicy;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.equiguard.config.AppConfig;
import com.equiguard.exceptions.AIProcessingException;
import com.equiguard.models.EthicsAuditResult;
import com.equiguard.models.PiiEntityModel;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Logger;

/**
 * Enterprise AIService handling language APIs with strict error handling and
 * domain logic encapsulation.
 * <p>
 * Authentication priority: if a key environment variable is set it is used
 * directly (AzureKeyCredential); otherwise the service falls back to
 * DefaultAzureCredential (Managed Identity / workload identity).
 */
public class AIService {

    private static final Logger logger = Logger.getLogger(AIService.class.getName());
    private static volatile AIService instance;

    /** Maximum retry attempts for transient Azure AI service errors. */
    private static final int MAX_RETRIES = 3;

    private final TextAnalyticsClient languageClient;
    private final ContentSafetyClient safetyClient;

    private AIService() {
        logger.info("Starting initialization of Azure AI Services...");
        try {
            String langEndpoint = AppConfig.getRequiredEnv("AI_LANGUAGE_ENDPOINT");
            String safetyEndpoint = AppConfig.getRequiredEnv("CONTENT_SAFETY_ENDPOINT");

            this.languageClient = buildLanguageClient(langEndpoint);
            this.safetyClient = buildSafetyClient(safetyEndpoint);

            logger.info("Azure AI Services initialized successfully.");
        } catch (Exception e) {
            String msg = "Failed to initialize Azure AI Clients. Verify environment config.";
            logger.severe(msg + " Cause: " + e.getMessage());
            throw new AIProcessingException(msg, e);
        }
    }

    /**
     * Package-private constructor for unit testing — accepts pre-built clients.
     */
    AIService(TextAnalyticsClient languageClient, ContentSafetyClient safetyClient) {
        this.languageClient = languageClient;
        this.safetyClient = safetyClient;
    }

    private TextAnalyticsClient buildLanguageClient(String endpoint) {
        String key = AppConfig.getOptionalEnv("AI_LANGUAGE_KEY", null);
        var builder = new TextAnalyticsClientBuilder()
                .endpoint(endpoint)
                .retryPolicy(new RetryPolicy(
                        new ExponentialBackoff(MAX_RETRIES, Duration.ofSeconds(1), Duration.ofSeconds(16))));
        if (key != null) {
            builder.credential(new AzureKeyCredential(key));
        } else {
            logger.info("AI_LANGUAGE_KEY not set — using DefaultAzureCredential (Managed Identity).");
            builder.credential(new DefaultAzureCredentialBuilder().build());
        }
        return builder.buildClient();
    }

    private ContentSafetyClient buildSafetyClient(String endpoint) {
        String key = AppConfig.getOptionalEnv("CONTENT_SAFETY_KEY", null);
        var builder = new ContentSafetyClientBuilder().endpoint(endpoint);
        if (key != null) {
            builder.credential(new AzureKeyCredential(key));
        } else {
            logger.info("CONTENT_SAFETY_KEY not set — using DefaultAzureCredential (Managed Identity).");
            builder.credential(new DefaultAzureCredentialBuilder().build());
        }
        return builder.buildClient();
    }

    /**
     * Singleton Pattern (Thread-safe double-checked locking).
     */
    public static AIService getInstance() {
        if (instance == null) {
            synchronized (AIService.class) {
                if (instance == null) {
                    instance = new AIService();
                }
            }
        }
        return instance;
    }

    public record PiiResult(String maskedText, List<PiiEntityModel> entities) {
    }

    /**
     * Intercepts and masks Personally Identifiable Information from the content.
     *
     * @param text     the raw text to analyse
     * @param language BCP-47 language tag (e.g. "es", "en") for improved accuracy
     */
    public PiiResult maskPII(String text, String language) {
        if (text == null || text.isBlank()) {
            return new PiiResult(text, new ArrayList<>());
        }

        try {
            PiiEntityCollection piiEntities = languageClient.recognizePiiEntities(text, language);
            List<PiiEntityModel> extractedEntities = new ArrayList<>();

            // Sort by descending offset to safely replace string parts without
            // altering the remaining indices
            List<PiiEntity> sortedEntities = piiEntities.stream()
                    .sorted(Comparator.comparingInt(PiiEntity::getOffset).reversed())
                    .toList();

            StringBuilder maskedBuilder = new StringBuilder(text);

            for (PiiEntity entity : sortedEntities) {
                extractedEntities.add(new PiiEntityModel(
                        entity.getText(),
                        entity.getCategory().toString(),
                        entity.getSubcategory() != null ? entity.getSubcategory() : null,
                        entity.getConfidenceScore(),
                        entity.getOffset(),
                        entity.getLength()));

                // Masking: [CategoryName] (e.g. [Person], [PhoneNumber])
                String replacement = "[" + entity.getCategory().toString() + "]";
                maskedBuilder.replace(entity.getOffset(), entity.getOffset() + entity.getLength(), replacement);
            }

            return new PiiResult(maskedBuilder.toString(), extractedEntities);
        } catch (Exception e) {
            logger.severe("Language Client Error: " + e.getMessage());
            throw new AIProcessingException("PII Masking execution failed.", e);
        }
    }

    /**
     * Executes Ethical Score Audit according to severe enterprise regulations.
     * Evaluates Hate, SelfHarm, Sexual, and Violence categories.
     *
     * @return EthicsAuditResult indicating overall score (0–100) and safety flag.
     */
    public EthicsAuditResult auditEthics(String text) {
        if (text == null || text.isBlank()) {
            return new EthicsAuditResult(100, true, "No content evaluated");
        }

        try {
            AnalyzeTextOptions request = new AnalyzeTextOptions(text);
            AnalyzeTextResult response = safetyClient.analyzeText(request);

            int maxSeverity = 0;
            StringBuilder detailsBuilder = new StringBuilder();

            if (response.getCategoriesAnalysis() != null) {
                for (TextCategoriesAnalysis categoryResult : response.getCategoriesAnalysis()) {
                    int sev = categoryResult.getSeverity() != null ? categoryResult.getSeverity() : 0;
                    if (sev > 0) {
                        detailsBuilder.append(String.format("[%s: Nivel %d] ", categoryResult.getCategory(), sev));
                    }
                    if (sev > maxSeverity) {
                        maxSeverity = sev;
                    }
                }
            }

            // Ethical Score (enterprise-grade):
            // Azure native severities: 0 (Safe), 2 (Low), 4 (Medium), 6 (High).
            // Score is worst-case driven: one category at level 6 makes the whole
            // text score 0, regardless of other categories.
            int ethicalScore = (int) Math.round(100.0 - ((maxSeverity / 6.0) * 100.0));

            // Zero-tolerance: any severity >= 2 marks content as unsafe.
            boolean isSafe = maxSeverity < 2;

            String detailStr = detailsBuilder.length() > 0
                    ? detailsBuilder.toString().trim()
                    : "All categories reported 0 severity. Fully compliant.";

            return new EthicsAuditResult(ethicalScore, isSafe, detailStr);

        } catch (Exception e) {
            logger.severe("Safety Client Error (Content Safety): " + e.getMessage());
            throw new AIProcessingException("Content Safety ethics audit failed. Cause: " + e.getMessage(), e);
        }
    }
}
