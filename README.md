# Temporal MCP Server

A Model Context Protocol (MCP) server that lets AI assistants observe and manage Temporal workflows. Built in Kotlin/JVM with a Node.js bridge for fast MCP initialization.

## Prerequisites

- JDK 17+
- Node.js (for the MCP bridge)
- Access to a Temporal server (local or Temporal Cloud)

## Build

```bash
./gradlew shadowJar
```

The JAR is created at `build/libs/temporal-mcp-server-1.0-SNAPSHOT.jar`.

## Configuration

The server is launched via `minimal-mcp.js`, which handles MCP protocol initialization and spawns the Java process.

### MCP Client Configuration

```json
{
  "mcpServers": {
    "temporal": {
      "command": "node",
      "args": [
        "path/to/minimal-mcp.js",
        "--namespace", "my-namespace",
        "--address", "my-host:7233"
      ],
      "env": {
        "TEMPORAL_API_KEY": "your-api-key"
      }
    }
  }
}
```

### Command-Line Arguments

| Argument | Required | Description |
|---|---|---|
| `--namespace` | Yes | Temporal namespace |
| `--address` | Yes | Temporal server address (e.g., `my-ns.abc.tmprl.cloud:7233`) |

### Environment Variables

| Variable | Description |
|---|---|
| `TEMPORAL_API_KEY` | API key for Temporal Cloud authentication |
| `TEMPORAL_CLIENT_CERT_PATH` | Path to mTLS client certificate file |
| `TEMPORAL_CLIENT_KEY_PATH` | Path to mTLS client key file |
| `TEMPORAL_CLIENT_CERT` | mTLS client certificate content (alternative to path) |
| `TEMPORAL_CLIENT_KEY` | mTLS client key content (alternative to path) |

## Authentication

### API Key (Temporal Cloud)

Set `TEMPORAL_API_KEY` in the `env` section of your MCP config. The key is sent as a `Bearer` token in gRPC metadata.

### mTLS Certificates

For mTLS authentication, provide either file paths or content directly:

```json
{
  "mcpServers": {
    "temporal": {
      "command": "node",
      "args": ["path/to/minimal-mcp.js", "--namespace", "my-ns", "--address", "my-host:7233"],
      "env": {
        "TEMPORAL_CLIENT_CERT_PATH": "/path/to/client.pem",
        "TEMPORAL_CLIENT_KEY_PATH": "/path/to/client.key"
      }
    }
  }
}
```

## Tools

### Workflow Observation

| Tool | Description |
|---|---|
| `list_workflows` | Lists workflows in the namespace. Supports `query` filter and `pageSize` (1-1000, default 10). |
| `get_workflow_status` | Gets status of a specific workflow by `workflowId` (optional `runId`). Returns `parentWorkflowId`, `parentRunId`, `taskQueue`, `closeTime`, `memo`, and `searchAttributes`. |
| `get_workflow_history` | Retrieves the complete event history of a workflow with full event attributes. |
| `query_workflow` | Executes a query on a running workflow (`workflowId`, `queryType`, optional `args`). |

### Workflow Actions

| Tool | Description |
|---|---|
| `start_workflow` | Starts a new workflow (`workflowId`, `workflowType`, `taskQueue`, optional `args`). |
| `send_signal` | Sends a signal to a running workflow (`workflowId`, `signalName`, optional `signalArgs`). |
| `reset_workflow` | Resets a workflow to a specific point in its history, creating a new run. Supports `eventId` or `resetType: "last_workflow_task"` to auto-detect the last WorkflowTaskCompleted event. |
| `terminate_workflow` | Terminates a running workflow (`workflowId`, optional `reason`). |

### Utilities

| Tool | Description |
|---|---|
| `wait_for_activity` | Polls until a workflow reaches a specific activity (`workflowId`, `activityName`, optional `timeoutSeconds` 1-3600). |
| `send_webhook` | Sends an HTTP POST webhook (`webhookUrl`, `payload`, optional `headers`). Useful for completing async activities. |

## Development

### Running Tests

```bash
./gradlew test
```

### Architecture

`minimal-mcp.js` acts as a bridge: it responds immediately to the MCP `initialize` handshake, then spawns the Java process in the background. Once Java is ready, all subsequent requests are forwarded to it.
