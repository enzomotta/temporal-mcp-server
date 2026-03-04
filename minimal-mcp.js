#!/usr/bin/env node

const { spawn } = require('child_process');
const readline = require('readline');
const path = require('path');

// Parse command-line arguments
function parseArgs(argv) {
    const args = {};
    for (let i = 2; i < argv.length; i++) {
        if (argv[i] === '--namespace' && argv[i + 1]) {
            args.namespace = argv[++i];
        } else if (argv[i] === '--address' && argv[i + 1]) {
            args.address = argv[++i];
        } else if (argv[i] === '--help' || argv[i] === '-h') {
            args.help = true;
        }
    }
    return args;
}

const args = parseArgs(process.argv);

if (args.help) {
    console.error(`Usage: node minimal-mcp.js --namespace <namespace> --address <address>

Required arguments:
  --namespace    Temporal namespace
  --address      Temporal server address (e.g., my-ns.abc.tmprl.cloud:7233)

Environment variables (credentials):
  TEMPORAL_API_KEY             API key for Temporal Cloud
  TEMPORAL_CLIENT_CERT_PATH    Path to mTLS client certificate
  TEMPORAL_CLIENT_KEY_PATH     Path to mTLS client key
  TEMPORAL_CLIENT_CERT         mTLS client certificate content (alternative to path)
  TEMPORAL_CLIENT_KEY          mTLS client key content (alternative to path)

Example MCP client config:
  {
    "mcpServers": {
      "temporal": {
        "command": "node",
        "args": ["path/to/minimal-mcp.js", "--namespace", "my-ns", "--address", "my-host:7233"],
        "env": { "TEMPORAL_API_KEY": "secret-key" }
      }
    }
  }`);
    process.exit(0);
}

if (!args.namespace || !args.address) {
    console.error('Error: --namespace and --address are required arguments.');
    console.error('Run with --help for usage information.');
    process.exit(1);
}

// Resolve JAR path relative to this script's directory
const JAVA_JAR_PATH = path.join(__dirname, 'build', 'libs', 'temporal-mcp-server-1.0-SNAPSHOT.jar');

// Build environment for the Java process: args become env vars, plus pass through credential env vars
const TEMPORAL_CONFIG = {
    TEMPORAL_ADDRESS: args.address,
    TEMPORAL_NAMESPACE: args.namespace,
};

// Pass through credential env vars if set
const CREDENTIAL_VARS = [
    'TEMPORAL_API_KEY',
    'TEMPORAL_CLIENT_CERT_PATH',
    'TEMPORAL_CLIENT_KEY_PATH',
    'TEMPORAL_CLIENT_CERT',
    'TEMPORAL_CLIENT_KEY',
];
for (const varName of CREDENTIAL_VARS) {
    if (process.env[varName]) {
        TEMPORAL_CONFIG[varName] = process.env[varName];
    }
}

console.error('Temporal MCP Server: Starting Java bridge with fast initialization');

// Immediately respond to MCP initialize to prevent timeout
const rl = readline.createInterface({
    input: process.stdin,
    output: process.stdout,
    terminal: false
});

let javaProcess = null;
let javaReady = false;
let pendingRequests = [];

// Start Java process in background
function startJavaProcess() {
    console.error('Temporal MCP Server: Starting Java process...');

    javaProcess = spawn('java', ['-jar', JAVA_JAR_PATH], {
        env: { ...process.env, ...TEMPORAL_CONFIG },
        stdio: ['pipe', 'pipe', 'pipe']
    });

    javaProcess.on('error', (error) => {
        console.error('Temporal MCP Server: Java process error:', error.message);
    });

    javaProcess.on('exit', (code, signal) => {
        console.error(`Temporal MCP Server: Java process exited with code ${code}, signal ${signal}`);
        process.exit(code || 0);
    });

    // Log Java stderr
    javaProcess.stderr.on('data', (data) => {
        const message = data.toString().trim();
        console.error('Java Server:', message);

        // Detect when Java server is ready
        if (message.includes('MCP Server started, waiting for requests')) {
            console.error('Temporal MCP Server: Java process ready, waiting 500ms for full initialization...');

            // Add a small delay to ensure Java is fully ready
            setTimeout(() => {
                javaReady = true;
                console.error('Temporal MCP Server: Java process confirmed ready, processing pending requests');

                // Process any pending requests
                while (pendingRequests.length > 0) {
                    const request = pendingRequests.shift();
                    console.error('Temporal MCP Server: Processing queued request:', request);
                    javaProcess.stdin.write(request + '\n');
                }
            }, 500);
        }
    });

    // Forward Java stdout
    javaProcess.stdout.on('data', (data) => {
        const output = data.toString();
        console.error('Temporal MCP Server: Java response:', output.trim());
        process.stdout.write(output);
    });
}

// Handle incoming MCP messages
rl.on('line', (line) => {
    console.error('Temporal MCP Server: Received:', line);

    try {
        const request = JSON.parse(line);

        // Handle initialize immediately to prevent timeout
        if (request.method === 'initialize') {
            const response = {
                jsonrpc: "2.0",
                id: request.id,
                result: {
                    protocolVersion: "2024-11-05",
                    capabilities: {
                        tools: {}
                    },
                    serverInfo: {
                        name: "temporal-mcp-server",
                        version: "1.0.0"
                    }
                }
            };

            console.error('Temporal MCP Server: Sending immediate initialize response');
            console.log(JSON.stringify(response));

            // Start Java process after responding
            if (!javaProcess) {
                startJavaProcess();
            }

            return;
        }

        // Handle notifications
        if (request.method === 'notifications/initialized') {
            console.error('Temporal MCP Server: Received initialization notification');
            return;
        }

        // For other requests, either send to Java if ready, or queue
        if (javaReady && javaProcess) {
            javaProcess.stdin.write(line + '\n');
        } else {
            console.error('Temporal MCP Server: Queueing request until Java ready');
            pendingRequests.push(line);

            // Start Java process if not started
            if (!javaProcess) {
                startJavaProcess();
            }
        }

    } catch (error) {
        console.error('Temporal MCP Server: Error parsing request:', error.message);
        const errorResponse = {
            jsonrpc: "2.0",
            id: "unknown",
            error: {
                code: -32700,
                message: "Parse error"
            }
        };
        console.log(JSON.stringify(errorResponse));
    }
});

// Handle process termination
process.on('SIGINT', () => {
    console.error('Temporal MCP Server: Received SIGINT, terminating...');
    if (javaProcess) {
        javaProcess.kill('SIGTERM');
    }
    process.exit(0);
});

process.on('SIGTERM', () => {
    console.error('Temporal MCP Server: Received SIGTERM, terminating...');
    if (javaProcess) {
        javaProcess.kill('SIGTERM');
    }
    process.exit(0);
});

console.error('Temporal MCP Server: Ready for MCP requests');
