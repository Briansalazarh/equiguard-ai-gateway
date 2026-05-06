package com.equiguard.functions;

import com.equiguard.exceptions.AIProcessingException;
import com.equiguard.exceptions.ConfigurationException;
import com.equiguard.models.EquiGuardResponse;
import com.equiguard.models.EthicsAuditResult;
import com.equiguard.services.AIService;
import com.equiguard.services.CosmosService;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for EquiGuardTrigger — validates HTTP routing, validation, happy
 * paths, and error handling without connecting to any Azure service.
 */
@ExtendWith(MockitoExtension.class)
class EquiGuardTriggerTest {

    @Mock
    private AIService aiService;

    @Mock
    private CosmosService cosmosService;

    @Mock
    private HttpRequestMessage<Optional<String>> request;

    @Mock
    private ExecutionContext context;

    private EquiGuardTrigger trigger;

    @BeforeEach
    void setUp() {
        trigger = new EquiGuardTrigger(aiService, cosmosService);
        when(context.getLogger()).thenReturn(Logger.getLogger("test"));
        setupResponseBuilder();
    }

    // ─────────────────────────────────────────────────────────────────
    // Input validation
    // ─────────────────────────────────────────────────────────────────

    @Test
    void run_emptyBody_returns400() {
        when(request.getBody()).thenReturn(Optional.empty());

        HttpResponseMessage response = trigger.run(request, context);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void run_blankBody_returns400() {
        when(request.getBody()).thenReturn(Optional.of("   "));

        HttpResponseMessage response = trigger.run(request, context);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void run_invalidJson_returns400() {
        when(request.getBody()).thenReturn(Optional.of("{not valid json}"));

        HttpResponseMessage response = trigger.run(request, context);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void run_missingRequestId_returns400() {
        when(request.getBody()).thenReturn(Optional.of("{\"content\":\"hello\"}"));

        HttpResponseMessage response = trigger.run(request, context);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void run_contentTooLong_returns400() {
        String longContent = "a".repeat(EquiGuardRequest_MAX_LENGTH + 1);
        String body = "{\"requestId\":\"r1\",\"content\":\"" + longContent + "\"}";
        when(request.getBody()).thenReturn(Optional.of(body));

        HttpResponseMessage response = trigger.run(request, context);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ─────────────────────────────────────────────────────────────────
    // Happy path
    // ─────────────────────────────────────────────────────────────────

    @Test
    void run_validRequest_returns200WithAuditRecord() {
        when(request.getBody()).thenReturn(Optional.of(safePayload("req-001")));
        stubAiServicesHappyPath(true);
        stubCosmos();

        HttpResponseMessage response = trigger.run(request, context);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK);
        String body = (String) response.getBody();
        assertThat(body).contains("req-001");
        assertThat(body).contains("\"auditStatus\":\"OK\"");
    }

    @Test
    void run_unsafeContent_returns200WithBlockedStatus() {
        when(request.getBody()).thenReturn(Optional.of(safePayload("req-002")));
        stubAiServicesHappyPath(false); // ethics → isSafe=false
        stubCosmos();

        HttpResponseMessage response = trigger.run(request, context);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK);
        String body = (String) response.getBody();
        assertThat(body).contains("\"auditStatus\":\"BLOCKED\"");
    }

    @Test
    void run_defaultLanguage_usedWhenLanguageOmitted() {
        // No "language" field in JSON — should default to "es" silently
        when(request.getBody()).thenReturn(Optional.of(safePayload("req-003")));
        stubAiServicesHappyPath(true);
        stubCosmos();

        HttpResponseMessage response = trigger.run(request, context);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK);
    }

    // ─────────────────────────────────────────────────────────────────
    // Degraded mode — ethics audit failure continues gracefully
    // ─────────────────────────────────────────────────────────────────

    @Test
    void run_ethicsAuditFails_returns200WithDegradedStatus() {
        when(request.getBody()).thenReturn(Optional.of(safePayload("req-004")));

        AIService.PiiResult piiResult = new AIService.PiiResult("masked text", List.of());
        when(aiService.maskPII(anyString(), anyString())).thenReturn(piiResult);
        when(aiService.auditEthics(anyString()))
                .thenThrow(new AIProcessingException("Content Safety down"));
        stubCosmos();

        HttpResponseMessage response = trigger.run(request, context);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK);
        String body = (String) response.getBody();
        assertThat(body).contains("\"auditStatus\":\"DEGRADED\"");
    }

    // ─────────────────────────────────────────────────────────────────
    // Error handling
    // ─────────────────────────────────────────────────────────────────

    @Test
    void run_piiMaskingFails_returns500() {
        when(request.getBody()).thenReturn(Optional.of(safePayload("req-005")));
        when(aiService.maskPII(anyString(), anyString()))
                .thenThrow(new AIProcessingException("Language service down"));

        HttpResponseMessage response = trigger.run(request, context);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void run_configurationException_returns500() {
        when(request.getBody()).thenReturn(Optional.of(safePayload("req-006")));
        when(aiService.maskPII(anyString(), anyString()))
                .thenThrow(new ConfigurationException("Missing config"));

        HttpResponseMessage response = trigger.run(request, context);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void run_unexpectedException_returns500() {
        when(request.getBody()).thenReturn(Optional.of(safePayload("req-007")));
        when(aiService.maskPII(anyString(), anyString()))
                .thenThrow(new RuntimeException("Unexpected error"));

        HttpResponseMessage response = trigger.run(request, context);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    // ─────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────

    /** Maximum content length constant mirrored from EquiGuardRequest. */
    private static final int EquiGuardRequest_MAX_LENGTH = 5120;

    private String safePayload(String requestId) {
        return "{\"requestId\":\"" + requestId + "\",\"content\":\"Hello, this is a safe message.\"}";
    }

    private void stubAiServicesHappyPath(boolean isSafe) {
        AIService.PiiResult piiResult = new AIService.PiiResult("masked content", List.of());
        EthicsAuditResult ethicsResult = new EthicsAuditResult(isSafe ? 100 : 0, isSafe, "detail");
        when(aiService.maskPII(anyString(), anyString())).thenReturn(piiResult);
        when(aiService.auditEthics(anyString())).thenReturn(ethicsResult);
    }

    private void stubCosmos() {
        when(cosmosService.insertAuditRecord(any(EquiGuardResponse.class)))
                .thenReturn(Mono.just(sampleResponse()));
    }

    private EquiGuardResponse sampleResponse() {
        return new EquiGuardResponse("id", "orig", "masked", List.of(), 100, true,
                "2026-01-01T00:00:00Z", "OK");
    }

    /**
     * Wires up the mock HttpRequestMessage response builder chain so that the
     * trigger can call {@code request.createResponseBuilder(...).header(...).body(...).build()}.
     */
    private void setupResponseBuilder() {
        when(request.createResponseBuilder(any(HttpStatus.class))).thenAnswer(invocation -> {
            HttpStatus status = invocation.getArgument(0);
            return new MockHttpResponseBuilder(status);
        });
    }

    // ─────────────────────────────────────────────────────────────────
    // MockHttpResponseBuilder — minimal builder for assertions
    // ─────────────────────────────────────────────────────────────────

    private static class MockHttpResponseBuilder implements HttpResponseMessage.Builder {
        private HttpStatus status;
        private Object body;

        MockHttpResponseBuilder(HttpStatus status) {
            this.status = status;
        }

        @Override
        public HttpResponseMessage.Builder status(com.microsoft.azure.functions.HttpStatusType httpStatusType) {
            if (httpStatusType instanceof HttpStatus hs) {
                this.status = hs;
            }
            return this;
        }

        @Override
        public HttpResponseMessage.Builder header(String key, String value) {
            return this;
        }

        @Override
        public HttpResponseMessage.Builder body(Object body) {
            this.body = body;
            return this;
        }

        @Override
        public HttpResponseMessage build() {
            return new MockHttpResponse(status, body);
        }
    }

    private static class MockHttpResponse implements HttpResponseMessage {
        private final HttpStatus status;
        private final Object body;

        MockHttpResponse(HttpStatus status, Object body) {
            this.status = status;
            this.body = body;
        }

        @Override
        public HttpStatus getStatus() {
            return status;
        }

        @Override
        public String getHeader(String key) {
            return null;
        }

        @Override
        public Object getBody() {
            return body;
        }
    }
}
