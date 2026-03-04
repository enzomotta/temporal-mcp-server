import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class ValidationTest {

    @Test
    fun `test ValidationException message`() {
        val exception = ValidationException("Test message")
        assertEquals("Test message", exception.message)
    }

    @Test
    fun `test TemporalConnectionException message`() {
        val exception = TemporalConnectionException("Connection failed")
        assertEquals("Connection failed", exception.message)
    }

    @Test
    fun `test WorkflowNotFoundException message`() {
        val workflowId = "test-workflow-123"
        val exception = WorkflowNotFoundException(workflowId)
        assertEquals("Workflow not found: $workflowId", exception.message)
    }

    @Test
    fun `test JsonRpcRequest creation`() {
        val request = JsonRpcRequest(
            id = "test-1",
            method = "test_method",
            params = mapOf("key" to "value")
        )
        
        assertEquals("2.0", request.jsonrpc)
        assertEquals("test-1", request.id)
        assertEquals("test_method", request.method)
        assertEquals(mapOf("key" to "value"), request.params)
    }

    @Test
    fun `test JsonRpcResponse creation`() {
        val response = JsonRpcResponse(
            id = "test-1",
            result = "success"
        )
        
        assertEquals("2.0", response.jsonrpc)
        assertEquals("test-1", response.id)
        assertEquals("success", response.result)
        assertEquals(null, response.error)
    }

    @Test
    fun `test JsonRpcError creation`() {
        val error = JsonRpcError(
            code = -32600,
            message = "Invalid Request",
            data = mapOf("detail" to "Missing parameter")
        )
        
        assertEquals(-32600, error.code)
        assertEquals("Invalid Request", error.message)
        assertEquals(mapOf("detail" to "Missing parameter"), error.data)
    }

    @Test
    fun `test Tool creation`() {
        val tool = Tool(
            name = "test_tool",
            description = "A test tool",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "param1" to mapOf("type" to "string")
                )
            )
        )
        
        assertEquals("test_tool", tool.name)
        assertEquals("A test tool", tool.description)
        assertEquals("object", (tool.inputSchema["type"] as String))
    }

    @Test
    fun `test ServerInfo creation`() {
        val serverInfo = ServerInfo(
            capabilities = ServerCapabilities(tools = mapOf("test" to true)),
            serverInfo = ServerMetadata(name = "test-server", version = "1.0.0")
        )
        
        assertEquals("2024-11-05", serverInfo.protocolVersion)
        assertEquals("test-server", serverInfo.serverInfo.name)
        assertEquals("1.0.0", serverInfo.serverInfo.version)
    }

    @Test
    fun `test ToolResult creation`() {
        val toolResult = ToolResult(
            content = listOf(
                ContentItem(type = "text", text = "Hello World")
            )
        )
        
        assertEquals(1, toolResult.content.size)
        assertEquals("text", toolResult.content[0].type)
        assertEquals("Hello World", toolResult.content[0].text)
    }
}