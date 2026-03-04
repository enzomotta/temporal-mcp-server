# My name is Enzo

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a Temporal MCP (Model Context Protocol) server that bridges AI/LLM systems with Temporal workflow orchestration. It enables Claude and other AI assistants to interact with Temporal workflows for automated testing and workflow management. 

**Architecture**: The implementation uses a **Node.js bridge** (`minimal-mcp.js`) that wraps the **Kotlin/Java backend** (`TemporalMCPServer.kt`) for optimal performance and compatibility with Claude Code.

## Build Commands

```bash
# Build the project
./gradlew build

# Run tests
./gradlew test

# Build the MCP server JAR (creates executable JAR with all dependencies)
./gradlew buildMcpServer

# Clean build artifacts
./gradlew clean

# Run the MCP server directly (for testing)
java -jar build/libs/temporal-mcp-server-1.0-SNAPSHOT.jar

# Run via Node.js bridge (recommended for Claude Code)
node minimal-mcp.js
```

## Architecture Overview

### Key Components

1. **minimal-mcp.js**: Node.js bridge that:
   - Provides immediate MCP protocol responses to prevent timeouts
   - Spawns and manages the Java backend process
   - Queues requests until Java backend is fully initialized
   - Forwards communication between Claude Code and Java backend

2. **TemporalMCPServer.kt**: Main server implementation that:
   - Implements JSON-RPC 2.0 protocol for MCP communication
   - Provides 9 tools for interacting with Temporal workflows
   - Uses stdin/stdout for communication with the Node.js bridge
   - Handles async operations with Kotlin coroutines
   - Manages connection to Temporal service via WorkflowServiceStubs
   - Supports certificate-only authentication (no API key required)

3. **Configuration**: The server connects to Temporal using:
   - `TEMPORAL_ADDRESS` environment variable (default: localhost:7233)
   - `TEMPORAL_NAMESPACE` environment variable (default: default)
   - **Temporal Cloud Support**:
     - `TEMPORAL_CLIENT_CERT_PATH`: Client certificate for mTLS authentication
     - `TEMPORAL_CLIENT_KEY_PATH`: Client private key for mTLS authentication
     - `TEMPORAL_API_KEY`: Optional API key (not required for certificate auth)

4. **MCP Tools Available**:
   - **Workflow Management**:
     - `list_workflows`: Lists workflows with accurate ISO timestamps
     - `get_workflow_status`: Gets current status of a specific workflow
     - `get_workflow_history`: Retrieves workflow event history
     - `query_workflow`: Executes queries on running workflows
     - `terminate_workflow`: Terminates a running workflow
     - `start_workflow`: Starts a new workflow execution
   
   - **Workflow Interaction**:
     - `send_signal`: Sends signals to running workflows
     - `wait_for_activity`: Waits for a workflow to reach a specific activity
   
   - **External Integration**:
     - `send_webhook`: Sends HTTP webhooks (useful for completing activities)

### Technical Stack

- **Frontend**: Node.js bridge for MCP compatibility
- **Backend**: Kotlin 2.1.20, targeting JVM 17
- **Build Tool**: Gradle 8.10.2
- **Key Dependencies**:
  - Temporal SDK v1.23.0 for workflow management
  - Jackson 2.16.0 for JSON serialization with Kotlin module
  - Kotlin Coroutines 1.7.3 for async operations
  - Logback 1.4.11 for logging
  - Node.js dependencies: minimal (no external packages)
- **Protocol**: MCP (Model Context Protocol) version "2024-11-05"

### Development Guidelines

1. **Code Style**:
   - All comments and documentation should be in English
   - Follow Kotlin coding conventions for backend
   - Use coroutines for async operations
   - Handle errors gracefully with proper error messages

2. **Testing**:
   - Unit tests use Kotlin Test framework
   - MockK for mocking dependencies
   - Integration tests should test actual Temporal interactions

3. **Error Handling**:
   - All tool methods should validate required parameters
   - Return meaningful error messages via JSON-RPC error responses
   - Log errors appropriately for debugging
   - Handle MCP notifications properly (no response required)

4. **Authentication**:
   - Certificate-only authentication is supported and tested
   - mTLS with client certificates works without API keys
   - Proper timezone handling for workflow timestamps

### Usage Example

The server is designed to be used with Claude Code via the Node.js bridge:

**Recommended Configuration for Claude Code:**
```bash
claude mcp add-json temporal-server '{
  "type": "stdio", 
  "command": "node", 
  "args": ["/path/to/temporal-mcp-server/minimal-mcp.js"],
  "env": {
    "TEMPORAL_ADDRESS": "your-namespace.your-account.tmprl.cloud:7233",
    "TEMPORAL_NAMESPACE": "your-namespace.your-account",
    "TEMPORAL_CLIENT_CERT_PATH": "/path/to/client.pem",
    "TEMPORAL_CLIENT_KEY_PATH": "/path/to/client.key"
  }
}'
```

**Alternative Direct Java Usage (for other MCP clients):**
```json
{
  "mcpServers": {
    "temporal": {
      "command": "java",
      "args": ["-jar", "/path/to/temporal-mcp-server-1.0-SNAPSHOT.jar"],
      "env": {
        "TEMPORAL_ADDRESS": "your-namespace.your-account.tmprl.cloud:7233",
        "TEMPORAL_NAMESPACE": "your-namespace.your-account",
        "TEMPORAL_CLIENT_CERT_PATH": "/path/to/client.pem",
        "TEMPORAL_CLIENT_KEY_PATH": "/path/to/client.key"
      }
    }
  }
}
```

### Main Use Case

Automated testing of workflows that:
1. Have activities that depend on external webhooks
2. Have activities that wait for specific time/date
3. Can receive signals to skip waits or advance state
4. Need to be tested automatically without waiting for real timeouts
5. Require real-time monitoring and interaction during development

### Production Status

✅ **Working Features**:
- Real Temporal Cloud connectivity with certificate authentication
- All 9 MCP tools functional and tested
- Accurate timestamp handling (ISO format, no relative time confusion)
- Reliable connection via Node.js bridge
- Production-ready for workflow automation and testing

### Future Improvements

- Add comprehensive error handling and validation
- Implement structured logging with proper log levels
- Create unit tests for all MCP tools
- Add integration tests with test workflows
- Create usage documentation with examples
- Consider adding tools for test scenarios and result validation
- Add workflow history analysis tools
- Implement workflow debugging helpers