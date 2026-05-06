package com.equiguard.functions;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.HttpMethod;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;
import com.microsoft.azure.functions.annotation.AuthorizationLevel;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.HttpTrigger;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import com.equiguard.exceptions.AIProcessingException;
import com.equiguard.exceptions.ConfigurationException;
import com.equiguard.models.EquiGuardRequest;
import com.equiguard.models.EquiGuardResponse;
import com.equiguard.models.EthicsAuditResult;
import com.equiguard.models.ProblemDetail;
import com.equiguard.services.AIService;
import com.equiguard.services.CosmosService;

/**
 * Clean Architecture Entrypoint for the EquiGuard Serverless Middleware.
 * Sole responsibility is HTTP binding and delegating to Business Services.
 */
public class EquiGuardTrigger {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /** Timeout in seconds for each individual AI service call. */
    private static final long AI_TIMEOUT_SECONDS = 15L;

    private static final String ENDPOINT_PATH = "/api/EquiGuardAudit";

    private final AIService aiService;
    private final CosmosService cosmosService;

    /** Default constructor used by the Azure Functions runtime. */
    public EquiGuardTrigger() {
        this.aiService = AIService.getInstance();
        this.cosmosService = CosmosService.getInstance();
    }

    /** Package-private constructor for unit testing — accepts pre-built services. */
    EquiGuardTrigger(AIService aiService, CosmosService cosmosService) {
        this.aiService = aiService;
        this.cosmosService = cosmosService;
    }

    @FunctionName("EquiGuardAudit")
    public HttpResponseMessage run(
            @HttpTrigger(name = "req", methods = {
                    HttpMethod.POST }, authLevel = AuthorizationLevel.FUNCTION) HttpRequestMessage<Optional<String>> request,
            final ExecutionContext context) {

        context.getLogger().info("🚀 [START] EquiGuard Middleware processing new request...");

        try {
            // 1. Input Validation
            String body = request.getBody().orElse(null);
            if (body == null || body.isBlank()) {
                context.getLogger().warning("❌ [VALIDATION] Request body is empty.");
                return problemResponse(request, HttpStatus.BAD_REQUEST,
                        ProblemDetail.badRequest("Request body is empty. JSON payload is required.",
                                ENDPOINT_PATH));
            }

            EquiGuardRequest dataRequest;
            try {
                dataRequest = objectMapper.readValue(body, EquiGuardRequest.class);
            } catch (JsonProcessingException e) {
                context.getLogger().warning("❌ [VALIDATION] Failed to deserialize JSON: " + e.getOriginalMessage());
                return problemResponse(request, HttpStatus.BAD_REQUEST,
                        ProblemDetail.badRequest("Invalid JSON payload format.", ENDPOINT_PATH));
            }

            final String requestId = dataRequest.requestId();
            context.getLogger().info("[" + requestId + "] Request validated. Language: " + dataRequest.language());

            // 2. Dependency Resolution
            // (injected via constructor; singletons in production, mocks in tests)

            // 3. Parallel AI Processing using virtual threads (Java 21)
            AIService.PiiResult piiResult;
            EthicsAuditResult auditResult;
            String auditStatus;

            context.getLogger().info("[" + requestId + "] 🛡️ Launching parallel AI processing...");

            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {

                var piiFuture = CompletableFuture
                        .supplyAsync(() -> aiService.maskPII(dataRequest.content(), dataRequest.language()), executor)
                        .orTimeout(AI_TIMEOUT_SECONDS, TimeUnit.SECONDS);

                var ethicsFuture = CompletableFuture
                        .supplyAsync(() -> aiService.auditEthics(dataRequest.content()), executor)
                        .orTimeout(AI_TIMEOUT_SECONDS, TimeUnit.SECONDS);

                // PII masking is mandatory — fail hard if it cannot complete
                try {
                    piiResult = piiFuture.get();
                    context.getLogger().info("[" + requestId + "] ✅ PII Masking complete. Entities found: "
                            + piiResult.entities().size());
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    // orTimeout() wraps TimeoutException inside ExecutionException
                    if (cause instanceof java.util.concurrent.TimeoutException) {
                        throw new AIProcessingException(
                                "PII masking timed out after " + AI_TIMEOUT_SECONDS + "s.", cause);
                    }
                    throw cause instanceof AIProcessingException
                            ? (AIProcessingException) cause
                            : new AIProcessingException("PII masking failed unexpectedly.", cause);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AIProcessingException("PII masking was interrupted.", e);
                }

                // Ethics audit is best-effort — degrade gracefully on failure
                try {
                    auditResult = ethicsFuture.get();
                    auditStatus = auditResult.isSafe() ? "OK" : "BLOCKED";
                    context.getLogger().info("[" + requestId + "] ✅ Ethics audit complete. Score: "
                            + auditResult.ethicalScore() + ", isSafe: " + auditResult.isSafe()
                            + ", piiEntities: " + piiResult.entities().size());
                } catch (ExecutionException e) {
                    context.getLogger().warning("[" + requestId
                            + "] ⚠️ Ethics audit unavailable (degraded mode): " + e.getMessage());
                    auditResult = new EthicsAuditResult(0, false, "DEGRADED: Ethics audit unavailable.");
                    auditStatus = "DEGRADED";
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    context.getLogger().warning("[" + requestId + "] ⚠️ Ethics audit interrupted (degraded mode).");
                    auditResult = new EthicsAuditResult(0, false, "DEGRADED: Processing interrupted.");
                    auditStatus = "DEGRADED";
                }
            }

            // 4. Construct Immutable Audit Record
            EquiGuardResponse responseRecord = new EquiGuardResponse(
                    requestId,
                    dataRequest.content(),
                    piiResult.maskedText(),
                    piiResult.entities(),
                    auditResult.ethicalScore(),
                    auditResult.isSafe(),
                    ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT),
                    auditStatus);

            // 5. Persist the log asynchronously
            context.getLogger().info("[" + requestId + "] 💾 Persisting Audit Record to Cosmos DB...");
            cosmosService.insertAuditRecord(responseRecord).block();

            // 6. Response
            context.getLogger()
                    .info("[" + requestId + "] ✅ [END] Workflow complete. auditStatus=" + auditStatus);
            return request.createResponseBuilder(HttpStatus.OK)
                    .header("Content-Type", "application/json")
                    .body(objectMapper.writeValueAsString(responseRecord))
                    .build();

        } catch (IllegalArgumentException e) {
            context.getLogger().log(Level.WARNING, "Domain validation exception: " + e.getMessage());
            return problemResponse(request, HttpStatus.BAD_REQUEST,
                    ProblemDetail.badRequest(e.getMessage(), ENDPOINT_PATH));
        } catch (ConfigurationException e) {
            context.getLogger().log(Level.SEVERE, "Configuration Fault: " + e.getMessage());
            return problemResponse(request, HttpStatus.INTERNAL_SERVER_ERROR,
                    ProblemDetail.configurationError(ENDPOINT_PATH));
        } catch (AIProcessingException e) {
            context.getLogger().log(Level.SEVERE, "AI Services Fault: ", e);
            return problemResponse(request, HttpStatus.INTERNAL_SERVER_ERROR,
                    ProblemDetail.internalError(ENDPOINT_PATH));
        } catch (Exception e) {
            context.getLogger().log(Level.SEVERE, "Unhandled Middleware Exception: ", e);
            return problemResponse(request, HttpStatus.INTERNAL_SERVER_ERROR,
                    ProblemDetail.internalError(ENDPOINT_PATH));
        }
    }

    private HttpResponseMessage problemResponse(
            HttpRequestMessage<Optional<String>> request,
            HttpStatus status,
            ProblemDetail problem) {
        try {
            return request.createResponseBuilder(status)
                    .header("Content-Type", "application/problem+json")
                    .body(objectMapper.writeValueAsString(problem))
                    .build();
        } catch (JsonProcessingException ex) {
            // Fallback: should never happen with a well-defined record
            return request.createResponseBuilder(status)
                    .header("Content-Type", "application/json")
                    .body("{\"status\":" + status.value() + "}")
                    .build();
        }
    }
}
