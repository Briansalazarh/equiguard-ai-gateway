package com.equiguard.services;

import com.azure.cosmos.CosmosAsyncClient;
import com.azure.cosmos.CosmosAsyncContainer;
import com.azure.cosmos.CosmosClientBuilder;
import com.equiguard.config.AppConfig;
import com.equiguard.exceptions.ConfigurationException;
import com.equiguard.models.EquiGuardResponse;

import java.util.logging.Logger;
import reactor.core.publisher.Mono;

/**
 * Enterprise service for asynchronously persisting Audit Records to Cosmos DB
 * NoSQL.
 */
public class CosmosService {

    private static final Logger logger = Logger.getLogger(CosmosService.class.getName());

    private static volatile CosmosService instance;
    private final CosmosAsyncContainer container;

    private CosmosService() {
        logger.info("Initializing Enterprise Cosmos DB Client...");
        try {
            String endpoint = AppConfig.getRequiredEnv("COSMOS_DB_ENDPOINT");
            String key = AppConfig.getRequiredEnv("COSMOS_DB_KEY");
            String databaseName = AppConfig.getRequiredEnv("COSMOS_DB_DATABASE_NAME");
            String containerName = AppConfig.getRequiredEnv("COSMOS_DB_CONTAINER_NAME");

            CosmosAsyncClient client = new CosmosClientBuilder()
                    .endpoint(endpoint)
                    .key(key)
                    .buildAsyncClient();

            this.container = client.getDatabase(databaseName).getContainer(containerName);
            logger.info("Cosmos DB Container loaded securely.");
        } catch (ConfigurationException e) {
            throw e; // Bubble up configuration exception directly
        } catch (Exception e) {
            logger.severe("Failed to initialize Cosmos Service: " + e.getMessage());
            throw new RuntimeException("Cosmos Initialization Exception", e);
        }
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
     * Store the enriched audit output async using Reactor.
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
