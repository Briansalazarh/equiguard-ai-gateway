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
import java.util.logging.Level;

import com.equiguard.exceptions.AIProcessingException;
import com.equiguard.exceptions.ConfigurationException;
import com.equiguard.models.EquiGuardRequest;
import com.equiguard.models.EquiGuardResponse;
import com.equiguard.models.EthicsAuditResult;
import com.equiguard.services.AIService;
import com.equiguard.services.CosmosService;

/**
 * Clean Architecture Entrypoint for the EquiGuard Serverless Middleware.
 * Sole responsibility is HTTP binding and delegating to Business Services.
 */
public class EquiGuardTrigger {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @FunctionName("EquiGuardAudit")
    public HttpResponseMessage run(
            @HttpTrigger(name = "req", methods = {
                    HttpMethod.POST }, authLevel = AuthorizationLevel.FUNCTION) HttpRequestMessage<Optional<String>> request,
            final ExecutionContext context) {

        context.getLogger().info("\n=======================================================");
        context.getLogger().info("🚀 [START] EquiGuard Middleware processing new request...");
        context.getLogger().info("=======================================================");

        try {
            // 1. Input Validation
            String body = request.getBody().orElse(null);
            if (body == null || body.isBlank()) {
                context.getLogger().warning("❌ [VALIDATION FAILED] Request body is empty. Returning 400 Bad Request.");
                return request.createResponseBuilder(HttpStatus.BAD_REQUEST)
                        .header("Content-Type", "application/json")
                        .body("{\"error\":\"Request body is empty. JSON Cannot be null.\", \"status\": 400}")
                        .build();
            }

            EquiGuardRequest dataRequest;
            try {
                dataRequest = objectMapper.readValue(body, EquiGuardRequest.class);
            } catch (JsonProcessingException e) {
                context.getLogger().warning("❌ [VALIDATION FAILED] Failed to deserialize JSON: " + e.getMessage());
                return request.createResponseBuilder(HttpStatus.BAD_REQUEST)
                        .header("Content-Type", "application/json")
                        .body("{\"error\":\"Invalid JSON payload format.\", \"status\": 400}")
                        .build();
            }

            // 2. Dependency Resolution
            AIService aiService = AIService.getInstance();
            CosmosService cosmosService = CosmosService.getInstance();

            // 3. Execution Core Logistics
            context.getLogger().info("🛡️ [ORCHESTRATION] Invoking AIService: Privacy Shield (PII Masking)...");
            AIService.PiiResult piiResult = aiService.maskPII(dataRequest.content());

            context.getLogger().info("⚖️ [ORCHESTRATION] Invoking AIService: Fairness Auditor (Ethical Check)...");
            EthicsAuditResult auditResult = aiService.auditEthics(dataRequest.content());

            // 4. Construct Immutable Audit Record
            EquiGuardResponse responseRecord = new EquiGuardResponse(
                    dataRequest.requestId(),
                    dataRequest.content(),
                    piiResult.maskedText(),
                    piiResult.entities(),
                    auditResult.ethicalScore(),
                    auditResult.isSafe(),
                    ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT));

            // 5. Persist the log natively
            context.getLogger().info("💾 [ORCHESTRATION] Invoking CosmosService: Saving AuditRecord into NoSQL...");
            cosmosService.insertAuditRecord(responseRecord).block();

            // 6. Response Construction
            context.getLogger().info("✅ [END] Workflow completed successfully! Returning EquiGuardResponse with 200 OK.");
            return request.createResponseBuilder(HttpStatus.OK)
                    .header("Content-Type", "application/json")
                    .body(objectMapper.writeValueAsString(responseRecord))
                    .build();

        } catch (IllegalArgumentException e) {
            context.getLogger().log(Level.WARNING, "Domain Validation exception: " + e.getMessage());
            return request.createResponseBuilder(HttpStatus.BAD_REQUEST)
                    .header("Content-Type", "application/json")
                    .body("{\"error\":\"" + e.getMessage() + "\"}")
                    .build();
        } catch (ConfigurationException e) {
            context.getLogger().log(Level.SEVERE, "Configuration Fault: " + e.getMessage());
            return request.createResponseBuilder(HttpStatus.INTERNAL_SERVER_ERROR)
                    .header("Content-Type", "application/json")
                    .body("{\"error\":\"Server Configuration Exception. Cannot proceed.\", \"details\":\""
                            + e.getMessage() + "\"}")
                    .build();
        } catch (AIProcessingException e) {
            context.getLogger().log(Level.SEVERE, "AI Services Fault: ", e);
            return request.createResponseBuilder(HttpStatus.INTERNAL_SERVER_ERROR)
                    .header("Content-Type", "application/json")
                    .body("{\"error\":\"Security Audit Failure\"}")
                    .build();
        } catch (Exception e) {
            context.getLogger().log(Level.SEVERE, "Unhandled Middleware Exception: ", e);
            return request.createResponseBuilder(HttpStatus.INTERNAL_SERVER_ERROR)
                    .header("Content-Type", "application/json")
                    .body("{\"error\":\"Security Audit Failure\"}")
                    .build();
        }
    }
}
