package com.equiguard.services;

import com.azure.cosmos.CosmosAsyncContainer;
import com.azure.cosmos.models.CosmosItemResponse;
import com.equiguard.models.EquiGuardResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for CosmosService — verifies Reactor pipeline correctness without
 * a real Cosmos DB endpoint.
 */
@ExtendWith(MockitoExtension.class)
class CosmosServiceTest {

    @Mock
    private CosmosAsyncContainer container;

    private CosmosService cosmosService;

    @BeforeEach
    void setUp() {
        cosmosService = new CosmosService(container);
    }

    @Test
    @SuppressWarnings("unchecked")
    void insertAuditRecord_success_returnsRecord() {
        EquiGuardResponse record = sampleResponse("req-001", "OK");

        CosmosItemResponse<EquiGuardResponse> mockResponse = mock(CosmosItemResponse.class);
        when(container.createItem(any(EquiGuardResponse.class)))
                .thenReturn(Mono.just(mockResponse));

        EquiGuardResponse result = cosmosService.insertAuditRecord(record).block();

        assertThat(result).isNotNull();
        assertThat(result.id()).isEqualTo("req-001");
        assertThat(result.auditStatus()).isEqualTo("OK");
    }

    @Test
    @SuppressWarnings("unchecked")
    void insertAuditRecord_cosmosError_propagatesError() {
        EquiGuardResponse record = sampleResponse("req-002", "OK");

        when(container.createItem(any(EquiGuardResponse.class)))
                .thenReturn(Mono.error(new RuntimeException("Cosmos unavailable")));

        Mono<EquiGuardResponse> result = cosmosService.insertAuditRecord(record);

        // Blocking on an error Mono should throw the underlying exception
        assertThatThrownBy(result::block)
                .isInstanceOf(Exception.class);
    }

    // ─────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────

    private EquiGuardResponse sampleResponse(String id, String auditStatus) {
        return new EquiGuardResponse(
                id,
                "original content",
                "masked content",
                List.of(),
                100,
                true,
                "2026-01-01T00:00:00Z",
                auditStatus);
    }
}
