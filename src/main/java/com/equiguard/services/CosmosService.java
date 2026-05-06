package com.equiguard.services;

import com.azure.cosmos.CosmosAsyncClient;
import com.azure.cosmos.CosmosAsyncContainer;
import com.azure.cosmos.CosmosClientBuilder;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.equiguard.config.AppConfig;
import com.equiguard.exceptions.ConfigurationException;
import com.equiguard.models.EquiGuardResponse;

import java.util.logging.Logger;
import reactor.core.publisher.Mono;

/**
 * Enterprise service for asynchronously persisting Audit Records to Cosmos DB
 * NoSQL.
 * <p>
 * Authentication priority: if {@code COSMOS_DB_KEY} is set it is used directly;
 * otherwise the service falls back to DefaultAzureCredential (Managed
 * Identity).
 */
public class CosmosService {

    private static final Logger logger = Logger.getLogger(CosmosService.class.getName());

    private static volatile CosmosService instance;
    private final CosmosAsyncContainer container;

    private CosmosService() {
        logger.info("Initializing Enterprise Cosmos DB Client...");
        try {
            String endpoint = AppConfig.getRequiredEnv("COSMOS_DB_ENDPOINT");
            String databaseName = AppConfig.getRequiredEnv("COSMOS_DB_DATABASE_NAME");
            String containerName = AppConfig.getRequiredEnv("COSMOS_DB_CONTAINER_NAME");
            String key = AppConfig.getOptionalEnv("COSMOS_DB_KEY", null);

            var builder = new CosmosClientBuilder().endpoint(endpoint);
            if (key != null) {
                builder.key(key);
            } else {
                logger.info("COSMOS_DB_KEY not set — using DefaultAzureCredential (Managed Identity).");
                builder.credential(new DefaultAzureCredentialBuilder().build());
            }

            CosmosAsyncClient client = builder.buildAsyncClient();
            this.container = client.getDatabase(databaseName).getContainer(containerName);
            logger.info("Cosmos DB Container loaded securely.");
        } catch (ConfigurationException e) {
            throw e; // Bubble up configuration exception directly
        } catch (Exception e) {
            logger.severe("Failed to initialize Cosmos Service: " + e.getMessage());
            throw new RuntimeException("Cosmos Initialization Exception", e);
        }
    }

    /**
     * Package-private constructor for unit testing — accepts a pre-built container.
     */
    CosmosService(CosmosAsyncContainer container) {
        this.container = container;
    }

    public static CosmosService getInstance() {
        if (instance == null) {
            synchronized (CosmosService.class) {
                if (instance == null) {
                    instance = new CosmosService();
                }
            }
        }
        return instance;
    }

    /**
     * Store the enriched audit output asynchronously using Reactor.
     */
    public Mono<EquiGuardResponse> insertAuditRecord(EquiGuardResponse record) {
        return container.createItem(record)
                .map(itemResponse -> {
                    logger.info("Audit Record persisted securely via Cosmos Async Data Plane. ID: " + record.id());
                    return record;
                })
                .doOnError(error -> {
                    logger.severe("CRITICAL: Cosmos DB Persistence Failed: " + error.getMessage());
                });
    }
}
