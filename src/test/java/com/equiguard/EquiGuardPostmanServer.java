package com.equiguard;

import com.equiguard.config.AppConfig;
import com.equiguard.models.EquiGuardRequest;
import com.equiguard.models.EquiGuardResponse;
import com.equiguard.models.EthicsAuditResult;
import com.equiguard.services.AIService;
import com.equiguard.services.CosmosService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;
import java.util.Map;

/**
 * 🚀 SUPER SERVIDOR DE DESARROLLO PARA POSTMAN
 * Este servidor levanta un endpoint real en http://localhost:7071/api/EquiGuardAudit
 * permitiéndote usar Postman sin necesidad de tener las Azure Functions Core Tools instaladas.
 */
public class EquiGuardPostmanServer {

    private static final ObjectMapper mapper = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        System.out.println("=======================================================");
        System.out.println("🛡️  EQUIGUARD AI - DEV SERVER FOR POSTMAN  🛡️");
        System.out.println("=======================================================");

        // 1. Cargar Configuración
        loadEnvFromSettings(new File("local.settings.json"));

        // 2. Inicializar Servidor en puerto 7071
        HttpServer server = HttpServer.create(new InetSocketAddress(7071), 0);
        
        // 3. Crear el Endpoint de Auditoría
        server.createContext("/api/EquiGuardAudit", new AuditHandler());
        
        server.setExecutor(null); // Default executor
        System.out.println("\n✅ SERVIDOR LISTO EN: http://localhost:7071/api/EquiGuardAudit");
        System.out.println("📡 Esperando peticiones de Postman...\n");
        server.start();
    }

    static class AuditHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            System.out.println("-------------------------------------------------------");
            System.out.println("🚀 [START] Nueva petición recibida desde Postman...");

            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "{\"error\":\"Only POST method is allowed\"}");
                return;
            }

            try {
                // 1. Leer Body
                byte[] bodyBytes = exchange.getRequestBody().readAllBytes();
                String body = new String(bodyBytes, StandardCharsets.UTF_8);
                
                if (body.isBlank()) {
                    System.out.println("❌ Error: Body vacío");
                    sendResponse(exchange, 400, "{\"error\":\"Empty body\"}");
                    return;
                }

                EquiGuardRequest dataRequest = mapper.readValue(body, EquiGuardRequest.class);
                System.out.println("🛡️  Procesando ID: " + dataRequest.requestId());

                // 2. Orquestar Servicios AI
                AIService aiService = AIService.getInstance();
                CosmosService cosmosService = CosmosService.getInstance();

                System.out.println("🔍 Ejecutando PII Masking...");
                AIService.PiiResult piiResult = aiService.maskPII(dataRequest.content());

                System.out.println("⚖️  Ejecutando Content Safety...");
                EthicsAuditResult auditResult = aiService.auditEthics(dataRequest.content());

                // 3. Construir Respuesta
                EquiGuardResponse responseRecord = new EquiGuardResponse(
                        dataRequest.requestId(),
                        dataRequest.content(),
                        piiResult.maskedText(),
                        piiResult.entities(),
                        auditResult.ethicalScore(),
                        auditResult.isSafe(),
                        ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT));

                // 4. Guardar en Cosmos
                System.out.println("💾 Persistiendo en Cosmos DB...");
                cosmosService.insertAuditRecord(responseRecord).block();

                // 5. Enviar Éxito
                String jsonResponse = mapper.writeValueAsString(responseRecord);
                System.out.println("✅ [END] Petición procesada y guardada. Enviando 200 OK.");
                sendResponse(exchange, 200, jsonResponse);

            } catch (Exception e) {
                System.err.println("💥 ERROR PROCESANDO PETICIÓN: " + e.getMessage());
                e.printStackTrace(); // Esto nos dirá exactamente qué recurso de Azure está fallando
                sendResponse(exchange, 500, "{\"error\":\"Security Audit Failure: " + e.getMessage() + "\"}");
            }
        }

        private void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(statusCode, responseBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        }
    }

    private static void loadEnvFromSettings(File file) throws Exception {
        if (!file.exists()) return;
        JsonNode root = mapper.readTree(file);
        JsonNode values = root.get("Values");
        if (values != null && values.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = values.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                System.setProperty(field.getKey(), field.getValue().asText());
            }
        }
    }
}
