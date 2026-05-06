package com.equiguard.services;

import com.azure.ai.contentsafety.ContentSafetyClient;
import com.azure.ai.contentsafety.models.AnalyzeTextOptions;
import com.azure.ai.contentsafety.models.AnalyzeTextResult;
import com.azure.ai.contentsafety.models.TextCategoriesAnalysis;
import com.azure.ai.textanalytics.TextAnalyticsClient;
import com.azure.ai.textanalytics.models.PiiEntityCollection;
import com.equiguard.exceptions.AIProcessingException;
import com.equiguard.models.EthicsAuditResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for AIService — covers PII masking edge-cases and the
 * ethics scoring formula at all Azure Content Safety severity levels.
 */
@ExtendWith(MockitoExtension.class)
class AIServiceTest {

    @Mock
    private TextAnalyticsClient languageClient;

    @Mock
    private ContentSafetyClient safetyClient;

    private AIService aiService;

    @BeforeEach
    void setUp() {
        aiService = new AIService(languageClient, safetyClient);
    }

    // ─────────────────────────────────────────────────────────────────
    // maskPII — edge cases that bypass the Azure SDK call
    // ─────────────────────────────────────────────────────────────────

    @Test
    void maskPII_nullText_returnsNullWithEmptyEntities() {
        AIService.PiiResult result = aiService.maskPII(null, "en");
        assertThat(result.maskedText()).isNull();
        assertThat(result.entities()).isEmpty();
    }

    @Test
    void maskPII_blankText_returnsBlankWithEmptyEntities() {
        AIService.PiiResult result = aiService.maskPII("   ", "en");
        assertThat(result.maskedText()).isBlank();
        assertThat(result.entities()).isEmpty();
    }

    @Test
    void maskPII_noEntitiesFound_returnsOriginalText() {
        PiiEntityCollection mockCollection = mock(PiiEntityCollection.class);
        when(mockCollection.stream()).thenReturn(Stream.empty());
        when(languageClient.recognizePiiEntities(anyString(), anyString())).thenReturn(mockCollection);

        AIService.PiiResult result = aiService.maskPII("Hello world", "en");

        assertThat(result.maskedText()).isEqualTo("Hello world");
        assertThat(result.entities()).isEmpty();
    }

    @Test
    void maskPII_clientThrowsException_wrapsInAIProcessingException() {
        when(languageClient.recognizePiiEntities(anyString(), anyString()))
                .thenThrow(new RuntimeException("Network error"));

        assertThatThrownBy(() -> aiService.maskPII("some text", "es"))
                .isInstanceOf(AIProcessingException.class)
                .hasMessageContaining("PII Masking execution failed.");
    }

    // ─────────────────────────────────────────────────────────────────
    // auditEthics — edge cases without Azure SDK
    // ─────────────────────────────────────────────────────────────────

    @Test
    void auditEthics_nullText_returns100ScoreAndSafe() {
        EthicsAuditResult result = aiService.auditEthics(null);
        assertThat(result.ethicalScore()).isEqualTo(100);
        assertThat(result.isSafe()).isTrue();
    }

    @Test
    void auditEthics_blankText_returns100ScoreAndSafe() {
        EthicsAuditResult result = aiService.auditEthics("  ");
        assertThat(result.ethicalScore()).isEqualTo(100);
        assertThat(result.isSafe()).isTrue();
    }

    // ─────────────────────────────────────────────────────────────────
    // auditEthics — scoring formula verification
    // Azure severities: 0 (safe), 2 (low), 4 (medium), 6 (high)
    // formula: score = round(100 - (severity / 6.0) * 100)
    // ─────────────────────────────────────────────────────────────────

    @Test
    void auditEthics_severity0_returns100ScoreAndSafe() {
        AnalyzeTextResult mockResult = mockAnalyzeResult(0);
        when(safetyClient.analyzeText(any(AnalyzeTextOptions.class))).thenReturn(mockResult);

        EthicsAuditResult result = aiService.auditEthics("clean text");

        assertThat(result.ethicalScore()).isEqualTo(100);
        assertThat(result.isSafe()).isTrue();
    }

    @Test
    void auditEthics_severity2_returns67ScoreAndUnsafe() {
        // score = round(100 - (2/6.0)*100) = round(100 - 33.33) = round(66.67) = 67
        AnalyzeTextResult mockResult = mockAnalyzeResult(2);
        when(safetyClient.analyzeText(any(AnalyzeTextOptions.class))).thenReturn(mockResult);

        EthicsAuditResult result = aiService.auditEthics("mildly toxic text");

        assertThat(result.ethicalScore()).isEqualTo(67);
        assertThat(result.isSafe()).isFalse();
    }

    @Test
    void auditEthics_severity4_returns33ScoreAndUnsafe() {
        // score = round(100 - (4/6.0)*100) = round(100 - 66.67) = round(33.33) = 33
        AnalyzeTextResult mockResult = mockAnalyzeResult(4);
        when(safetyClient.analyzeText(any(AnalyzeTextOptions.class))).thenReturn(mockResult);

        EthicsAuditResult result = aiService.auditEthics("moderately toxic text");

        assertThat(result.ethicalScore()).isEqualTo(33);
        assertThat(result.isSafe()).isFalse();
    }

    @Test
    void auditEthics_severity6_returns0ScoreAndUnsafe() {
        // score = round(100 - (6/6.0)*100) = 0
        AnalyzeTextResult mockResult = mockAnalyzeResult(6);
        when(safetyClient.analyzeText(any(AnalyzeTextOptions.class))).thenReturn(mockResult);

        EthicsAuditResult result = aiService.auditEthics("highly toxic text");

        assertThat(result.ethicalScore()).isEqualTo(0);
        assertThat(result.isSafe()).isFalse();
    }

    @Test
    void auditEthics_worstCaseDriven_usesMaxSeverity() {
        // One category severity 2, another severity 6 — score must use 6
        AnalyzeTextResult mockResult = mock(AnalyzeTextResult.class);
        TextCategoriesAnalysis catLow = mock(TextCategoriesAnalysis.class);
        TextCategoriesAnalysis catHigh = mock(TextCategoriesAnalysis.class);
        when(catLow.getSeverity()).thenReturn(2);
        when(catHigh.getSeverity()).thenReturn(6);
        when(mockResult.getCategoriesAnalysis()).thenReturn(List.of(catLow, catHigh));
        when(safetyClient.analyzeText(any(AnalyzeTextOptions.class))).thenReturn(mockResult);

        EthicsAuditResult result = aiService.auditEthics("mixed content");

        assertThat(result.ethicalScore()).isEqualTo(0);   // worst-case: severity 6
        assertThat(result.isSafe()).isFalse();
    }

    @Test
    void auditEthics_nullCategoriesAnalysis_returns100ScoreAndSafe() {
        AnalyzeTextResult mockResult = mock(AnalyzeTextResult.class);
        when(mockResult.getCategoriesAnalysis()).thenReturn(null);
        when(safetyClient.analyzeText(any(AnalyzeTextOptions.class))).thenReturn(mockResult);

        EthicsAuditResult result = aiService.auditEthics("some text");

        assertThat(result.ethicalScore()).isEqualTo(100);
        assertThat(result.isSafe()).isTrue();
    }

    @Test
    void auditEthics_clientThrowsException_wrapsInAIProcessingException() {
        when(safetyClient.analyzeText(any(AnalyzeTextOptions.class)))
                .thenThrow(new RuntimeException("Service unavailable"));

        assertThatThrownBy(() -> aiService.auditEthics("some text"))
                .isInstanceOf(AIProcessingException.class)
                .hasMessageContaining("Content Safety ethics audit failed.");
    }

    // ─────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────

    /** Creates a mock AnalyzeTextResult with a single category at the given severity. */
    private AnalyzeTextResult mockAnalyzeResult(int severity) {
        AnalyzeTextResult mockResult = mock(AnalyzeTextResult.class);
        TextCategoriesAnalysis mockCategory = mock(TextCategoriesAnalysis.class);
        when(mockCategory.getSeverity()).thenReturn(severity);
        when(mockResult.getCategoriesAnalysis()).thenReturn(List.of(mockCategory));
        return mockResult;
    }
}
