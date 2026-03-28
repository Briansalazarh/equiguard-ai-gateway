package com.equiguard;

import com.equiguard.models.EquiGuardRequest;
import com.equiguard.models.EquiGuardResponse;
import com.equiguard.models.EthicsAuditResult;
import com.equiguard.services.AIService;
import com.equiguard.services.CosmosService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;
import java.util.Map;

/**
 * 🚀 SIMULADOR DE INTEGRACIÓN PARA EQUIGUARD AI
 * Esta clase permite probar el Middleware COMPLETO (AI + Cosmos) sin necesidad 
 * de que el servidor de Azure Functions esté corriendo localmente.
 * ¡Perfecto para tu demo y validación rápida!
 */
public class EquiGuardLocalTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void testFullMiddlewareFlow() {
        System.out.println("=================================================");
        System.out.println("🛡️  EQUIGUARD AI - SIMULADOR DE MIDDLEWARE  🛡️");
        System.out.println("=================================================\n");

        try {
            // 1. CARGAR VARIABLES DE ENTORNO DESDE local.settings.json
            System.out.println("[1/4] Cargando configuración desde local.settings.json...");
            loadEnvFromSettings(new File("local.settings.json"));

            // 2. INICIALIZAR SERVICIOS (SINGLETONS)
            System.out.println("[2/4] Inicializando Servicios de Azure AI y Cosmos DB...");
            AIService aiService = AIService.getInstance();
            CosmosService cosmosService = CosmosService.getInstance();

            // 3. PREPARAR REQUEST DE PRUEBA (DATA TOUGH)
            String content = "Hola, soy Juan Pérez. Mi DNI es 12.345.678-9 y mi teléfono el 987-654-321. " +
                           "Me molesta mucho la gente de otros países, creo que no deberían permitirles entrar.";
            
            EquiGuardRequest request = new EquiGuardRequest("local-sim-test-001", content);
            System.out.println("\n--- ENTRADA (PAYLOAD ORIGINAL) ---");
            System.out.println("ID: " + request.requestId());
            System.out.println("Contenido: " + request.content());

            // 4. EJECUTAR LÓGICA DE NEGOCIO (EL "CEREBRO")
            System.out.println("\n[3/4] Procesando Escudo de Privacidad (PII Masking)...");
            AIService.PiiResult piiResult = aiService.maskPII(request.content());

            System.out.println("[4/4] Auditando Ética y Sesgos (Content Safety)...");
            EthicsAuditResult auditResult = aiService.auditEthics(request.content());

            // 5. CONSTRUIR RESPUESTA FINAL
            EquiGuardResponse response = new EquiGuardResponse(
                request.requestId(),
                request.content(),
                piiResult.maskedText(),
                piiResult.entities(),
                auditResult.ethicalScore(),
                auditResult.isSafe(),
                ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT)
            );

            // 6. PERSISTIR EN COSMOS DB (ASINCRÓNICO -> BLOQUEAMOS PARA EL TEST)
            System.out.print("💾 Guardando Auditoría en Azure Cosmos DB... ");
            cosmosService.insertAuditRecord(response).block();
            System.out.println("¡GUARDADO CON ÉXITO!");

            // 7. MOSTRAR RESULTADOS FINALES (LA MAGIA)
            System.out.println("\n=================================================");
            System.out.println("✨ RESULTADOS DE AUDITORÍA ✨");
            System.out.println("=================================================");
            System.out.println("PRIVACY SHIELD (ENMASCARADO):");
            System.out.println(">> " + response.maskedContent());
            System.out.println("\nFAIRNESS AUDITOR:");
            System.out.println(">> Ethical Score (0-100): " + response.ethicalScore());
            System.out.println(">> Contenido Seguro: " + (response.isSafe() ? "SÍ ✅" : "NO ❌"));
            System.out.println(">> Traza: " + auditResult.severityDetails());
            System.out.println("=================================================");
            
            System.out.println("\n🚀 ¡TODO FUNCIONA PERFECTO! Estás listo para ganar.");

        } catch (Exception e) {
            System.err.println("\n❌ ERROR CRÍTICO EN LA SIMULACIÓN:");
            e.printStackTrace();
        }
    }

    /**
     * Hack para inyectar variables de entorno en tiempo de ejecución para el test.
     */
    private static void loadEnvFromSettings(File file) throws Exception {
        if (!file.exists()) {
            throw new RuntimeException("No se encontró local.settings.json. Créalo antes de correr el test.");
        }
        JsonNode root = mapper.readTree(file);
        JsonNode values = root.get("Values");
        if (values != null && values.isObject()) {
            // Este método usa reflexión o simplemente un log para avisar al usuario.
            // Nota: En Java moderno no se puede modificar System.getenv() fácilmente, 
            // así que las clases de servicio ya deben estar adaptadas para leer de Props si falla Env.
            // Para este test, imprimiremos qué variables estamos detectando.
            Iterator<Map.Entry<String, JsonNode>> fields = values.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                System.setProperty(field.getKey(), field.getValue().asText());
            }
        }
    }
}
