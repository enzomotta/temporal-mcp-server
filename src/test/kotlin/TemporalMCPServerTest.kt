import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Basic integration tests for TemporalMCPServer.
 * Note: These tests don't actually connect to a Temporal server.
 * For comprehensive testing, use proper mocking or a test Temporal instance.
 */
class TemporalMCPServerTest {

    @Test
    fun `test server can be instantiated with custom parameters`() {
        // Test that we can create a server with custom address and namespace
        // This doesn't test actual connection, just instantiation
        val address = "custom:7233"
        val namespace = "custom-namespace"
        
        // We expect this to throw TemporalConnectionException since there's no server running
        try {
            TemporalMCPServer(address, namespace)
        } catch (e: TemporalConnectionException) {
            assertTrue(e.message!!.contains("Failed to connect to Temporal server"))
            assertTrue(e.message!!.contains(address))
        }
    }

    @Test
    fun `test server uses default parameters when none provided`() {
        // Test that the server uses defaults when no parameters are provided
        // This doesn't test actual connection, just instantiation with defaults
        
        // We expect this to throw TemporalConnectionException since there's no server running
        try {
            TemporalMCPServer()
        } catch (e: TemporalConnectionException) {
            assertTrue(e.message!!.contains("Failed to connect to Temporal server"))
            assertTrue(e.message!!.contains("localhost:7233"))
        }
    }

    @Test
    fun `test JsonRpcRequest creation with default jsonrpc version`() {
        val request = JsonRpcRequest(
            id = "test-1",
            method = "initialize"
        )
        
        assertEquals("2.0", request.jsonrpc)
        assertEquals("test-1", request.id)
        assertEquals("initialize", request.method)
        assertEquals(null, request.params)
    }

    @Test
    fun `test JsonRpcResponse creation with default jsonrpc version`() {
        val response = JsonRpcResponse(
            id = "test-1",
            result = "success"
        )
        
        assertEquals("2.0", response.jsonrpc)
        assertEquals("test-1", response.id)
        assertEquals("success", response.result)
        assertEquals(null, response.error)
    }
}