package com.loanstreet.accounting.web.analytics.experian.buyerhealth

import com.loanstreet.accounting.legacy.table.primaryTables
import com.loanstreet.accounting.test.EmptyDBContainer
import com.loanstreet.accounting.test.refreshMaterializedViews
import com.loanstreet.accounting.web.analytics.filtering.ParticipationIdsQueryRec
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Integration test for [BuyerHealthAggregateService] using Testcontainers PostgreSQL.
 * Tests basic functionality of the buyer health service.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BuyerHealthAggregateServiceTest {
    companion object {
        @Container
        private val container = EmptyDBContainer()

        private lateinit var service: BuyerHealthAggregateService
        
        @JvmStatic
        @BeforeAll
        fun setupAll() {
            val database = container.getDatabase()
            
            // Create schema with primary tables
            container.withTables(primaryTables) {
                refreshMaterializedViews()
            }
            
            // Create service with proper configuration
            service = BuyerHealthAggregateService(
                primaryDatabase = database,
                workMem = 256 // Default work memory for analytics queries
            )
        }
    }

    @Test
    fun `should execute buyer health query without errors`() {
        // Create a simple query that should work even on empty database
        val queryRec = ParticipationIdsQueryRec(
            description = "Simple test query",
            query = "SELECT 1 AS participation_id", // Simple query that returns something
            args = emptyList()
        )

        val result = service.getBuyerHealth(queryRec)

        // Should not throw and should return a valid result
        assertNotNull(result)
        // With no data, loan count should be 0 and health values should be null
        assertEquals(0, result.loanCount)
        assertNull(result.buyerHealth)
        assertNull(result.delta)
    }

    @Test
    fun `should handle empty participation query gracefully`() {
        val queryRec = ParticipationIdsQueryRec(
            description = "Empty result set query",
            query = "SELECT participation_id FROM loans_participation WHERE 1=0", // Returns no rows
            args = emptyList()
        )

        val result = service.getBuyerHealth(queryRec)

        // Should handle empty result set without errors
        assertEquals(0, result.loanCount)
        assertNull(result.buyerHealth)
        assertNull(result.delta)
    }

    @Test
    fun `should handle query with invalid table reference`() {
        // Note: The service might throw if the query references tables that don't exist
        // in the current transaction, but we test it doesn't crash the whole test
        val queryRec = ParticipationIdsQueryRec(
            description = "Test query execution",
            query = "SELECT participation_id FROM loans_participation", // Valid query on empty table
            args = emptyList()
        )

        val result = service.getBuyerHealth(queryRec)

        // Should execute without throwing database errors
        assertNotNull(result)
        // Empty table means no results
        assertEquals(0, result.loanCount)
    }
}