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
import com.equiguard.config.AppConfig;
import com.equiguard.exceptions.AIProcessingException;
import com.equiguard.models.EthicsAuditResult;
import com.equiguard.models.PiiEntityModel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Logger;

/**
 * Enterprise AIService handling language APIs with strict error handling and
 * domain logic encapsulation.
 */
public class AIService {

    private static final Logger logger = Logger.getLogger(AIService.class.getName());
    private static volatile AIService instance;

    private final TextAnalyticsClient languageClient;
    private final ContentSafetyClient safetyClient;

    private AIService() {
        logger.info("Starting initialization of Azure AI Services...");
        try {
            // Utilizando el AppConfig (Fail-Fast pattern)
            String langEndpoint = AppConfig.getRequiredEnv("AI_LANGUAGE_ENDPOINT");
            String langKey = AppConfig.getRequiredEnv("AI_LANGUAGE_KEY");
            String safetyEndpoint = AppConfig.getRequiredEnv("CONTENT_SAFETY_ENDPOINT");
            String safetyKey = AppConfig.getRequiredEnv("CONTENT_SAFETY_KEY");

            this.languageClient = new TextAnalyticsClientBuilder()
                    .endpoint(langEndpoint)
                    .credential(new AzureKeyCredential(langKey))
                    .buildClient();

            this.safetyClient = new ContentSafetyClientBuilder()
                    .endpoint(safetyEndpoint)
                    .credential(new AzureKeyCredential(safetyKey))
                    .buildClient();

            logger.info("Azure AI Services initialized successfully.");
        } catch (Exception e) {
            String msg = "Failed to initialize Azure AI Clients. Verify environment config.";
            logger.severe(msg + " Cause: " + e.getMessage());
            throw new AIProcessingException(msg, e);
        }
    }

    /**
     * Singleton Pattern (Thread-safe)
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
     */
    public PiiResult maskPII(String text) {
        if (text == null || text.isBlank()) {
            return new PiiResult(text, new ArrayList<>());
        }

        try {
            PiiEntityCollection piiEntities = languageClient.recognizePiiEntities(text);
            List<PiiEntityModel> extractedEntities = new ArrayList<>();

            // Sort by descending offset to safely replace string parts iteratively sin
            // alterar los índices restantes
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

                // Enmascaramiento: [CategoryName] (ej: [Person], [DateTime])
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
     * @return EthicsAuditResult indicating overall score (0-100) and safety
     *         boolean.
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

            // LÓGICA DE AUDITORÍA ÉTICA (Enterprise-Grade)
            // Severidades nativas de Azure: 0 (Safe), 2 (Low), 4 (Medium), 6 (High)

            // 1. Calculamos un "Ethical Score" del 0 al 100 donde 100 = Perfectamente
            // Ético/Seguro.
            // Para proteger el sistema, nos basamos en el peor escenario (maxSeverity) en
            // lugar de un promedio.
            // Si hay un nivel 6 en 'Hate' y 0 en el resto, el texto NO ES 75% seguro, es 0%
            // seguro.
            int ethicalScore = (int) Math.round(100.0 - ((maxSeverity / 6.0) * 100.0));

            // 2. Determinamos la bandera crítica (Política de Tolerancia Inteligente)
            // Cualquier categoría >= 2 hace que el contenido se marque como no seguro.
            boolean isSafe = maxSeverity < 2;

            // 3. Generamos traza descriptiva para auditoría compliance
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
