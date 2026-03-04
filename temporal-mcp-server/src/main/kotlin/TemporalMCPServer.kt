import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowClientOptions
import io.temporal.client.WorkflowOptions
import io.temporal.serviceclient.WorkflowServiceStubs
import io.temporal.serviceclient.WorkflowServiceStubsOptions
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import io.grpc.Metadata
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.HttpURLConnection
import java.net.URI
import java.time.Duration

// Custom exceptions for better error handling
class ValidationException(message: String) : Exception(message)
class TemporalConnectionException(message: String, cause: Throwable? = null) : Exception(message, cause)
class WorkflowNotFoundException(workflowId: String) : Exception("Workflow not found: $workflowId")

// Models for the MCP protocol
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: Any?,
    val method: String,
    val params: Map<String, Any>? = null
)

data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: Any,
    val result: Any? = null,
    val error: JsonRpcError? = null
) {
    fun toMap(): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>(
            "jsonrpc" to jsonrpc,
            "id" to id
        )
        if (result != null) {
            map["result"] = result
        }
        if (error != null) {
            map["error"] = error
        }
        return map
    }
}

data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: Any? = null
)

data class Tool(
    val name: String,
    val description: String,
    val inputSchema: Map<String, Any>
)

data class ServerInfo(
    val protocolVersion: String = "2024-11-05",
    val capabilities: ServerCapabilities,
    val serverInfo: ServerMetadata
)

data class ServerCapabilities(
    val tools: Map<String, Any> = emptyMap()
)

data class ServerMetadata(
    val name: String,
    val version: String
)

data class ToolResult(
    val content: List<ContentItem>
)

data class ContentItem(
    val type: String,
    val text: String
)

class TemporalMCPServer(
    private val temporalAddress: String = "localhost:7233",
    private val namespace: String = "default",
    private val clientCertPath: String? = null,
    private val clientKeyPath: String? = null,
    private val clientCertContent: String? = null,
    private val clientKeyContent: String? = null,
    private val apiKey: String? = null
) {
    companion object {
        private val logger = LoggerFactory.getLogger(TemporalMCPServer::class.java)
    }
    
    private val mapper: ObjectMapper = jacksonObjectMapper()
    private val reader = BufferedReader(InputStreamReader(System.`in`))
    private val writer = PrintWriter(System.out, true)
    private var workflowClient: WorkflowClient? = null
    private var serviceStubs: WorkflowServiceStubs? = null

    init {
        logger.info("Initializing Temporal MCP Server - Address: $temporalAddress, Namespace: $namespace")
        logger.info("Client cert path: $clientCertPath, Client key path: $clientKeyPath, API key present: ${apiKey != null}")
        logger.info("MCP Server initialized, Temporal connection will be established on first use")
    }

    private fun getClientCert(): String? {
        return when {
            clientCertContent != null -> clientCertContent
            clientCertPath != null -> File(clientCertPath).readText()
            else -> null
        }
    }

    private fun getClientKey(): String? {
        return when {
            clientKeyContent != null -> clientKeyContent
            clientKeyPath != null -> File(clientKeyPath).readText()
            else -> null
        }
    }

    private fun ensureTemporalClient() {
        if (serviceStubs == null || workflowClient == null) {
            setupTemporalClient()
        }
    }

    private fun setupTemporalClient() {
        try {
            logger.debug("Creating WorkflowServiceStubs for address: $temporalAddress")
            
            val stubsOptionsBuilder = WorkflowServiceStubsOptions.newBuilder()

            // Configure TLS and authentication for Temporal Cloud
            val clientCert = getClientCert()
            val clientKey = getClientKey()
            
            if (clientCert != null && clientKey != null) {
                logger.debug("Configuring mTLS authentication for Temporal Cloud")
                logger.debug("Client cert length: ${clientCert.length}, Client key length: ${clientKey.length}")
                
                try {
                    val channelBuilder = NettyChannelBuilder.forTarget(temporalAddress)
                    
                    // Configure SSL/TLS
                    val sslContextBuilder = GrpcSslContexts.forClient()
                    
                    if (clientCert.isNotEmpty() && clientKey.isNotEmpty()) {
                        logger.debug("Setting up SSL context with client certificates")
                        // Add client certificate for mTLS
                        val certStream = ByteArrayInputStream(clientCert.toByteArray())
                        val keyStream = ByteArrayInputStream(clientKey.toByteArray())
                        
                        try {
                            sslContextBuilder.keyManager(certStream, keyStream)
                            logger.debug("SSL key manager configured successfully")
                        } catch (e: Exception) {
                            logger.error("Failed to configure SSL key manager", e)
                            throw TemporalConnectionException("Failed to configure SSL key manager: ${e.message}", e)
                        }
                    }
                    
                    val sslContext = sslContextBuilder.build()
                    channelBuilder.sslContext(sslContext)
                    logger.debug("SSL context built successfully")
                    
                    val channel = channelBuilder.build()
                    stubsOptionsBuilder.setChannel(channel)
                    logger.debug("gRPC channel configured with SSL")
                    
                } catch (e: Exception) {
                    logger.error("Failed to configure TLS/mTLS", e)
                    throw TemporalConnectionException("Failed to configure TLS/mTLS: ${e.message}", e)
                }
            } else {
                logger.debug("No client certificates provided, using standard connection")
                stubsOptionsBuilder.setTarget(temporalAddress)
            }

            // Add API key header if provided (for Temporal Cloud)
            if (apiKey != null && apiKey.isNotEmpty()) {
                logger.debug("Configuring API key authentication")
                stubsOptionsBuilder.addGrpcMetadataProvider { 
                    val metadata = Metadata()
                    val authKey = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)
                    metadata.put(authKey, "Bearer $apiKey")
                    metadata
                }
            }

            logger.debug("Building WorkflowServiceStubs")
            serviceStubs = WorkflowServiceStubs.newServiceStubs(stubsOptionsBuilder.build())

            logger.debug("Creating WorkflowClient for namespace: $namespace")
            workflowClient = WorkflowClient.newInstance(
                serviceStubs,
                WorkflowClientOptions.newBuilder()
                    .setNamespace(namespace)
                    .build()
            )
            logger.info("Successfully connected to Temporal server")
        } catch (e: Exception) {
            logger.error("Failed to connect to Temporal server at $temporalAddress", e)
            throw TemporalConnectionException("Failed to connect to Temporal server at $temporalAddress", e)
        }
    }

    fun start() = runBlocking {
        logger.info("MCP Server started, waiting for requests...")
        // Ensure stdout is immediately available for MCP clients
        System.out.flush()
        
        // Some MCP clients expect immediate availability signal
        // Send a newline to indicate readiness (this will be ignored by proper JSON-RPC)
        writer.println()
        writer.flush()
        System.out.flush()
        
        while (true) {
            try {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue

                logger.debug("Received request: $line")
                val request = mapper.readValue<JsonRpcRequest>(line)
                val response = handleRequest(request)

                // Only send response if one was generated (not for notifications)
                if (response != null) {
                    val responseJson = mapper.writeValueAsString(response.toMap())
                    logger.debug("Sending response: $responseJson")
                    writer.println(responseJson)
                    writer.flush()
                    System.out.flush() // Extra explicit flush
                }
            } catch (e: Exception) {
                logger.error("Error processing request", e)
                val errorResponse = JsonRpcResponse(
                    id = "unknown",
                    error = JsonRpcError(
                        code = -32700,
                        message = "Parse error: ${e.message}"
                    )
                )
                writer.println(mapper.writeValueAsString(errorResponse.toMap()))
                writer.flush()
                System.out.flush() // Extra explicit flush
            }
        }
        logger.info("MCP Server shutting down")
    }

    internal suspend fun handleRequest(request: JsonRpcRequest): JsonRpcResponse? {
        logger.debug("Handling ${request.method} request")
        
        // Handle notifications (no response needed)
        if (request.method.startsWith("notifications/")) {
            logger.debug("Received notification: ${request.method}")
            return null
        }
        
        // Require id for non-notification requests
        val requestId = request.id ?: "unknown"
        
        return try {
            val result = when (request.method) {
                "initialize" -> handleInitialize(request)
                "tools/list" -> handleToolsList()
                "tools/call" -> handleToolCall(request)
                else -> {
                    logger.warn("Unsupported method: ${request.method}")
                    throw IllegalArgumentException("Unsupported method: ${request.method}")
                }
            }

            JsonRpcResponse(id = requestId, result = result)
        } catch (e: ValidationException) {
            logger.warn("Validation error: ${e.message}")
            JsonRpcResponse(
                id = requestId,
                error = JsonRpcError(
                    code = -32602,
                    message = "Invalid params: ${e.message}"
                )
            )
        } catch (e: WorkflowNotFoundException) {
            logger.warn("Workflow not found: ${e.message}")
            JsonRpcResponse(
                id = requestId,
                error = JsonRpcError(
                    code = -32001,
                    message = e.message ?: "Workflow not found"
                )
            )
        } catch (e: TemporalConnectionException) {
            logger.error("Temporal connection error", e)
            JsonRpcResponse(
                id = requestId,
                error = JsonRpcError(
                    code = -32002,
                    message = "Temporal connection error: ${e.message}"
                )
            )
        } catch (e: IllegalArgumentException) {
            logger.warn("Invalid argument: ${e.message}")
            JsonRpcResponse(
                id = requestId,
                error = JsonRpcError(
                    code = -32601,
                    message = e.message ?: "Method not found"
                )
            )
        } catch (e: Exception) {
            logger.error("Internal error handling request", e)
            JsonRpcResponse(
                id = requestId,
                error = JsonRpcError(
                    code = -32603,
                    message = e.message ?: "Internal error"
                )
            )
        }
    }

    // Validation helper methods
    internal fun validateRequired(value: String?, paramName: String): String {
        if (value.isNullOrBlank()) {
            throw ValidationException("Parameter '$paramName' is required and cannot be empty")
        }
        return value
    }

    internal fun validateWorkflowId(workflowId: String?): String {
        val validated = validateRequired(workflowId, "workflowId")
        if (validated.length > 255) {
            throw ValidationException("workflowId cannot exceed 255 characters")
        }
        return validated
    }

    internal fun validateTimeoutSeconds(timeoutSeconds: Number?): Long {
        val timeout = timeoutSeconds?.toLong() ?: 30L
        if (timeout <= 0 || timeout > 3600) {
            throw ValidationException("timeoutSeconds must be between 1 and 3600 seconds")
        }
        return timeout
    }

    internal fun validatePageSize(pageSize: Number?): Int {
        val size = pageSize?.toInt() ?: 10
        if (size <= 0 || size > 1000) {
            throw ValidationException("pageSize must be between 1 and 1000")
        }
        return size
    }

    private fun handleInitialize(request: JsonRpcRequest): ServerInfo {
        return ServerInfo(
            capabilities = ServerCapabilities(
                tools = mapOf("" to true)
            ),
            serverInfo = ServerMetadata(
                name = "temporal-mcp-server",
                version = "1.0.0"
            )
        )
    }

    private fun handleToolsList(): Map<String, List<Tool>> {
        return mapOf(
            "tools" to listOf(
                Tool(
                    name = "list_workflows",
                    description = "Lists all workflows in the namespace",
                    inputSchema = mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "query" to mapOf(
                                "type" to "string",
                                "description" to "Query to filter workflows (optional)"
                            ),
                            "pageSize" to mapOf(
                                "type" to "number",
                                "description" to "Number of results per page",
                                "default" to 10
                            )
                        )
                    )
                ),
                Tool(
                    name = "get_workflow_status",
                    description = "Gets the status of a specific workflow",
                    inputSchema = mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "workflowId" to mapOf(
                                "type" to "string",
                                "description" to "Workflow ID"
                            ),
                            "runId" to mapOf(
                                "type" to "string",
                                "description" to "Workflow run ID (optional)"
                            )
                        ),
                        "required" to listOf("workflowId")
                    )
                ),
                Tool(
                    name = "get_workflow_history",
                    description = "Gets the event history of a workflow",
                    inputSchema = mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "workflowId" to mapOf(
                                "type" to "string",
                                "description" to "Workflow ID"
                            )
                        ),
                        "required" to listOf("workflowId")
                    )
                ),
                Tool(
                    name = "query_workflow",
                    description = "Executes a query on a workflow",
                    inputSchema = mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "workflowId" to mapOf(
                                "type" to "string",
                                "description" to "Workflow ID"
                            ),
                            "queryType" to mapOf(
                                "type" to "string",
                                "description" to "Query type"
                            ),
                            "args" to mapOf(
                                "type" to "array",
                                "description" to "Query arguments"
                            )
                        ),
                        "required" to listOf("workflowId", "queryType")
                    )
                ),
                Tool(
                    name = "terminate_workflow",
                    description = "Terminates a running workflow",
                    inputSchema = mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "workflowId" to mapOf(
                                "type" to "string",
                                "description" to "Workflow ID"
                            ),
                            "reason" to mapOf(
                                "type" to "string",
                                "description" to "Reason for terminating the workflow"
                            )
                        ),
                        "required" to listOf("workflowId")
                    )
                ),
                Tool(
                    name = "send_signal",
                    description = "Sends a signal to a workflow",
                    inputSchema = mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "workflowId" to mapOf(
                                "type" to "string",
                                "description" to "Workflow ID"
                            ),
                            "signalName" to mapOf(
                                "type" to "string",
                                "description" to "Signal name"
                            ),
                            "signalArgs" to mapOf(
                                "type" to "object",
                                "description" to "Signal arguments",
                                "additionalProperties" to true
                            )
                        ),
                        "required" to listOf("workflowId", "signalName")
                    )
                ),
                Tool(
                    name = "start_workflow",
                    description = "Starts a new workflow",
                    inputSchema = mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "workflowId" to mapOf(
                                "type" to "string",
                                "description" to "Workflow ID"
                            ),
                            "workflowType" to mapOf(
                                "type" to "string",
                                "description" to "Workflow type/name"
                            ),
                            "taskQueue" to mapOf(
                                "type" to "string",
                                "description" to "Workflow task queue"
                            ),
                            "args" to mapOf(
                                "type" to "object",
                                "description" to "Workflow arguments",
                                "additionalProperties" to true
                            )
                        ),
                        "required" to listOf("workflowId", "workflowType", "taskQueue")
                    )
                ),
                Tool(
                    name = "wait_for_activity",
                    description = "Waits for the workflow to reach a specific activity",
                    inputSchema = mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "workflowId" to mapOf(
                                "type" to "string",
                                "description" to "Workflow ID"
                            ),
                            "activityName" to mapOf(
                                "type" to "string",
                                "description" to "Activity name to wait for"
                            ),
                            "timeoutSeconds" to mapOf(
                                "type" to "number",
                                "description" to "Timeout in seconds",
                                "default" to 30
                            )
                        ),
                        "required" to listOf("workflowId", "activityName")
                    )
                ),
                Tool(
                    name = "send_webhook",
                    description = "Sends a webhook to complete an activity",
                    inputSchema = mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "webhookUrl" to mapOf(
                                "type" to "string",
                                "description" to "Webhook URL"
                            ),
                            "payload" to mapOf(
                                "type" to "object",
                                "description" to "Webhook payload",
                                "additionalProperties" to true
                            ),
                            "headers" to mapOf(
                                "type" to "object",
                                "description" to "HTTP headers",
                                "additionalProperties" to true
                            )
                        ),
                        "required" to listOf("webhookUrl", "payload")
                    )
                )
            )
        )
    }

    private suspend fun handleToolCall(request: JsonRpcRequest): ToolResult {
        val params = request.params ?: throw ValidationException("Missing parameters")
        val toolName = params["name"] as? String ?: throw ValidationException("Missing tool name")
        val arguments = params["arguments"] as? Map<String, Any> ?: emptyMap()

        logger.debug("Executing tool: $toolName with arguments: $arguments")

        val result = when (toolName) {
            "list_workflows" -> listWorkflows(arguments)
            "get_workflow_status" -> getWorkflowStatus(arguments)
            "get_workflow_history" -> getWorkflowHistory(arguments)
            "query_workflow" -> queryWorkflow(arguments)
            "terminate_workflow" -> terminateWorkflow(arguments)
            "send_signal" -> sendSignal(arguments)
            "start_workflow" -> startWorkflow(arguments)
            "wait_for_activity" -> waitForActivity(arguments)
            "send_webhook" -> sendWebhook(arguments)
            else -> throw IllegalArgumentException("Unknown tool: $toolName")
        }

        return ToolResult(
            content = listOf(
                ContentItem(
                    type = "text",
                    text = result
                )
            )
        )
    }

    private suspend fun listWorkflows(args: Map<String, Any>): String = coroutineScope {
        ensureTemporalClient()
        val query = args["query"] as? String ?: ""
        val pageSize = validatePageSize(args["pageSize"] as? Number)
        
        logger.debug("Listing workflows with query='$query', pageSize=$pageSize")

        try {
            val workflows = mutableListOf<Map<String, Any?>>()

            val listRequest = serviceStubs!!
                .blockingStub()
                .listWorkflowExecutions(
                    io.temporal.api.workflowservice.v1.ListWorkflowExecutionsRequest.newBuilder()
                        .setNamespace(namespace)
                        .setQuery(query)
                        .setPageSize(pageSize)
                        .build()
                )

            listRequest.executionsList.forEach { execution ->
                val startTimeSeconds = execution.startTime.seconds
                val executionTimeSeconds = if (execution.hasExecutionTime()) execution.executionTime.seconds else null
                
                workflows.add(mapOf(
                    "workflowId" to execution.execution.workflowId,
                    "runId" to execution.execution.runId,
                    "type" to execution.type.name,
                    "status" to execution.status.name,
                    "startTime" to java.time.Instant.ofEpochSecond(startTimeSeconds).toString(),
                    "executionTime" to if (executionTimeSeconds != null) java.time.Instant.ofEpochSecond(executionTimeSeconds).toString() else null,
                    "taskQueue" to execution.taskQueue
                ))
            }

            logger.debug("Found ${workflows.size} workflows")
            mapper.writeValueAsString(workflows)
        } catch (e: Exception) {
            logger.error("Failed to list workflows", e)
            throw TemporalConnectionException("Failed to list workflows: ${e.message}", e)
        }
    }

    private suspend fun getWorkflowStatus(args: Map<String, Any>): String = coroutineScope {
        ensureTemporalClient()
        val workflowId = args["workflowId"] as? String
            ?: throw IllegalArgumentException("workflowId is required")
        val runId = args["runId"] as? String

        // Use the service stubs directly to get workflow info
        val describeRequest = io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest.newBuilder()
            .setNamespace(namespace)
            .setExecution(
                io.temporal.api.common.v1.WorkflowExecution.newBuilder()
                    .setWorkflowId(workflowId)
                    .also { if (runId != null) it.setRunId(runId) }
                    .build()
            )
            .build()
        
        val description = serviceStubs!!.blockingStub().describeWorkflowExecution(describeRequest)

        val status = mapOf(
            "workflowId" to description.workflowExecutionInfo.execution.workflowId,
            "runId" to description.workflowExecutionInfo.execution.runId,
            "type" to description.workflowExecutionInfo.type.name,
            "status" to description.workflowExecutionInfo.status.name,
            "startTime" to description.workflowExecutionInfo.startTime.seconds,
            "historyLength" to description.workflowExecutionInfo.historyLength
        )

        mapper.writeValueAsString(status)
    }

    private suspend fun getWorkflowHistory(args: Map<String, Any>): String = coroutineScope {
        ensureTemporalClient()
        val workflowId = args["workflowId"] as? String
            ?: throw IllegalArgumentException("workflowId is required")

        val historyEvents = getWorkflowHistoryEvents(workflowId)
        val events = historyEvents.map { event ->
            mapOf<String, Any?>(
                "eventId" to event.eventId,
                "eventType" to event.eventType.name,
                "eventTime" to event.eventTime?.seconds
            )
        }

        mapper.writeValueAsString(events)
    }

    private fun getWorkflowHistoryEvents(workflowId: String): List<io.temporal.api.history.v1.HistoryEvent> {
        val history = serviceStubs!!
            .blockingStub()
            .getWorkflowExecutionHistory(
                io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryRequest.newBuilder()
                    .setNamespace(namespace)
                    .setExecution(
                        io.temporal.api.common.v1.WorkflowExecution.newBuilder()
                            .setWorkflowId(workflowId)
                            .build()
                    )
                    .build()
            )
        
        return history.history.eventsList
    }

    private suspend fun queryWorkflow(args: Map<String, Any>): String = coroutineScope {
        ensureTemporalClient()
        val workflowId = args["workflowId"] as? String
            ?: throw IllegalArgumentException("workflowId is required")
        val queryType = args["queryType"] as? String
            ?: throw IllegalArgumentException("queryType is required")
        val queryArgs = args["args"] as? List<Any> ?: emptyList()

        val workflow = workflowClient!!.newUntypedWorkflowStub(workflowId)
        val result = workflow.query(queryType, Any::class.java, *queryArgs.toTypedArray())

        mapper.writeValueAsString(result)
    }

    private suspend fun terminateWorkflow(args: Map<String, Any>): String = coroutineScope {
        ensureTemporalClient()
        val workflowId = args["workflowId"] as? String
            ?: throw IllegalArgumentException("workflowId is required")
        val reason = args["reason"] as? String ?: "Terminated via MCP"

        workflowClient!!.newUntypedWorkflowStub(workflowId).terminate(reason)

        "Workflow $workflowId terminated successfully"
    }

    private suspend fun sendSignal(args: Map<String, Any>): String = coroutineScope {
        ensureTemporalClient()
        val workflowId = args["workflowId"] as? String
            ?: throw IllegalArgumentException("workflowId is required")
        val signalName = args["signalName"] as? String
            ?: throw IllegalArgumentException("signalName is required")
        val signalArgs = args["signalArgs"] as? Map<String, Any> ?: emptyMap()

        workflowClient!!.newUntypedWorkflowStub(workflowId).signal(signalName, signalArgs)

        "Signal '$signalName' sent to workflow $workflowId"
    }

    private suspend fun startWorkflow(args: Map<String, Any>): String = coroutineScope {
        ensureTemporalClient()
        val workflowId = args["workflowId"] as? String
            ?: throw IllegalArgumentException("workflowId is required")
        val workflowType = args["workflowType"] as? String
            ?: throw IllegalArgumentException("workflowType is required")
        val taskQueue = args["taskQueue"] as? String
            ?: throw IllegalArgumentException("taskQueue is required")
        val workflowArgs = args["args"] as? Map<String, Any> ?: emptyMap()

        val options = WorkflowOptions.newBuilder()
            .setWorkflowId(workflowId)
            .setTaskQueue(taskQueue)
            .build()

        val workflow = workflowClient!!.newUntypedWorkflowStub(workflowType, options)
        val execution = workflow.start(workflowArgs)

        val result = mapOf(
            "workflowId" to workflowId,
            "runId" to execution.runId,
            "status" to "STARTED"
        )

        mapper.writeValueAsString(result)
    }

    private suspend fun waitForActivity(args: Map<String, Any>): String = coroutineScope {
        ensureTemporalClient()
        val workflowId = args["workflowId"] as? String
            ?: throw IllegalArgumentException("workflowId is required")
        val activityName = args["activityName"] as? String
            ?: throw IllegalArgumentException("activityName is required")
        val timeoutSeconds = (args["timeoutSeconds"] as? Number)?.toLong() ?: 30L

        val startTime = System.currentTimeMillis()
        val timeout = Duration.ofSeconds(timeoutSeconds)

        while (System.currentTimeMillis() - startTime < timeout.toMillis()) {
            // Use the service stubs directly to get workflow info
            val describeRequest = io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest.newBuilder()
                .setNamespace(namespace)
                .setExecution(
                    io.temporal.api.common.v1.WorkflowExecution.newBuilder()
                        .setWorkflowId(workflowId)
                        .build()
                )
                .build()
            
            val description = serviceStubs!!.blockingStub().describeWorkflowExecution(describeRequest)

            // Check pendingActivities to see if it's in the expected activity
            val pendingActivities = description.pendingActivitiesList
            if (pendingActivities.isNotEmpty()) {
                // Check if any pending activity matches our target
                val matchingActivity = pendingActivities.find { activity ->
                    activity.activityType.name == activityName ||
                    activity.activityType.name.contains(activityName)
                }
                
                if (matchingActivity != null) {
                    return@coroutineScope mapper.writeValueAsString(mapOf<String, Any?>(
                        "status" to "ACTIVITY_PENDING",
                        "workflowId" to workflowId,
                        "activityName" to matchingActivity.activityType.name,
                        "activityId" to matchingActivity.activityId,
                        "scheduledTime" to matchingActivity.scheduledTime?.seconds
                    ))
                }
            }

            // Check history for scheduled activities
            try {
                val historyEvents = getWorkflowHistoryEvents(workflowId)
                val hasScheduledActivity = historyEvents.any { event ->
                    event.eventType == io.temporal.api.enums.v1.EventType.EVENT_TYPE_ACTIVITY_TASK_SCHEDULED
                }
                
                if (hasScheduledActivity) {
                    // Check if the workflow has activities in progress based on history
                    return@coroutineScope mapper.writeValueAsString(mapOf<String, Any?>(
                        "status" to "ACTIVITY_DETECTED",
                        "workflowId" to workflowId,
                        "activityName" to activityName,
                        "message" to "Workflow has activity history"
                    ))
                }
            } catch (_: Exception) {
                // If getting history fails, continue trying
            }

            delay(1000)
        }

        throw IllegalStateException("Timeout waiting for activity '$activityName' in workflow $workflowId after ${timeoutSeconds}s")
    }

    private suspend fun sendWebhook(args: Map<String, Any>): String = coroutineScope {
        val webhookUrl = args["webhookUrl"] as? String
            ?: throw IllegalArgumentException("webhookUrl is required")
        val payload = args["payload"] as? Map<String, Any> ?: emptyMap()
        val headers = args["headers"] as? Map<String, String> ?: emptyMap()

        val url = URI(webhookUrl).toURL()
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")

            // Adicionar headers customizados
            headers.forEach { (key, value) ->
                connection.setRequestProperty(key, value)
            }

            // Enviar payload
            connection.outputStream.use { os ->
                os.write(mapper.writeValueAsString(payload).toByteArray())
            }

            val responseCode = connection.responseCode
            val response = if (responseCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "Error without response"
            }

            val result = mapOf(
                "status" to responseCode,
                "response" to response,
                "url" to webhookUrl
            )

            mapper.writeValueAsString(result)
        } finally {
            connection.disconnect()
        }
    }
}

fun main() {
    val temporalAddress = System.getenv("TEMPORAL_ADDRESS") ?: "localhost:7233"
    val namespace = System.getenv("TEMPORAL_NAMESPACE") ?: "default"
    
    // Temporal Cloud authentication
    val clientCert = System.getenv("TEMPORAL_CLIENT_CERT")
    val clientKey = System.getenv("TEMPORAL_CLIENT_KEY")
    val apiKey = System.getenv("TEMPORAL_API_KEY")
    
    // Support reading certificates from files
    val clientCertPath = System.getenv("TEMPORAL_CLIENT_CERT_PATH")
    val clientKeyPath = System.getenv("TEMPORAL_CLIENT_KEY_PATH")
    
    val server = TemporalMCPServer(
        temporalAddress = temporalAddress,
        namespace = namespace,
        clientCertPath = clientCertPath,
        clientKeyPath = clientKeyPath,
        clientCertContent = clientCert,
        clientKeyContent = clientKey,
        apiKey = apiKey
    )
    server.start()
}