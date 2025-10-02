# Using OpenTelemetry Java Agent with IntelliJ for Solr Tests

This guide explains how to use the OpenTelemetry Java Agent with IntelliJ IDEA run configurations to enable distributed tracing in Solr tests without modifying test code.

## Prerequisites

1. A running tracing backend (e.g., Jaeger)
2. IntelliJ IDEA with the Solr project imported
3. The OpenTelemetry Java Agent JAR

## Step 1: Download the OpenTelemetry Java Agent

Run the Gradle task to download the agent JAR:

```bash
./gradlew :solr:modules:opentelemetry:downloadOtelAgent
```

This downloads the agent to: `solr/modules/opentelemetry/build/agent/opentelemetry-javaagent.jar`

The file is gitignored (build directory) so you'll need to run this task once per checkout or after clean builds.

## Step 2: Set Up a Tracing Backend (e.g., Jaeger)

Start Jaeger using Docker:

```bash
docker run -d --name jaeger \
  -p 16686:16686 \
  -p 4317:4317 \
  jaegertracing/all-in-one:latest
```

Access Jaeger UI at: http://localhost:16686

## Step 3: Configure IntelliJ Run Configuration

### Option A: Modify an Existing Test Run Configuration

1. **Open Run/Debug Configurations**
   - Click on the configuration dropdown in the toolbar
   - Select "Edit Configurations..."

2. **Select or Create a Test Configuration**
   - Select an existing JUnit test configuration, or
   - Click "+" to create a new JUnit configuration

3. **Add VM Options**
   - In the "VM options" field, add:
   ```
   -javaagent:solr/modules/opentelemetry/build/agent/opentelemetry-javaagent.jar
   -Dotel.javaagent.configuration-file=solr/modules/opentelemetry/otel-agent-config.properties
   ```

   **Note:** Use absolute paths if IntelliJ has issues with relative paths:
   ```
   -javaagent:/absolute/path/to/solr/modules/opentelemetry/build/agent/opentelemetry-javaagent.jar
   -Dotel.javaagent.configuration-file=/absolute/path/to/solr/modules/opentelemetry/otel-agent-config.properties
   ```

4. **Apply and Save**
   - Click "Apply" then "OK"

### Option B: Create a Test Template with Agent Pre-configured

1. **Open Run/Debug Configurations**
   - Click on configuration dropdown → "Edit Configurations..."

2. **Edit Templates**
   - Click "Edit configuration templates..." at the bottom left
   - Select "JUnit" from the templates list

3. **Add VM Options to Template**
   - In "VM options", add the javaagent and config file parameters:
   ```
   -javaagent:solr/modules/opentelemetry/build/agent/opentelemetry-javaagent.jar
   -Dotel.javaagent.configuration-file=solr/modules/opentelemetry/otel-agent-config.properties
   ```

4. **Save Template**
   - Click "OK"
   - All new JUnit run configurations will now include the Java agent

## Step 4: Run Tests with Tracing

1. Run your test configuration as normal
2. Open Jaeger UI at http://localhost:16686
3. Select service "solr-test" from the dropdown
4. Click "Find Traces" to see your test execution traces

## Customizing the Configuration

Edit `solr/modules/opentelemetry/otel-agent-config.properties` to customize:

- **Service Name**: Change `otel.service.name` to distinguish different test runs
- **Endpoint**: Change `otel.exporter.otlp.endpoint` for different tracing backends
- **Sampling**: Change `otel.traces.sampler` for different sampling strategies
- **Resource Attributes**: Add custom attributes with `otel.resource.attributes`

## Alternative: Using Environment Variables

Instead of the properties file, you can set environment variables in the run configuration:

1. In the run configuration, go to "Environment variables"
2. Add the following variables:
   ```
   OTEL_SERVICE_NAME=solr-test
   OTEL_TRACES_EXPORTER=otlp
   OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317
   OTEL_EXPORTER_OTLP_PROTOCOL=grpc
   OTEL_TRACES_SAMPLER=always_on
   OTEL_METRICS_EXPORTER=none
   OTEL_LOGS_EXPORTER=none
   ```

3. Keep only the javaagent VM option:
   ```
   -javaagent:solr/modules/opentelemetry/build/agent/opentelemetry-javaagent.jar
   ```

## Comparison with TraceUtils Approach

| Approach | Pros | Cons |
|----------|------|------|
| **Java Agent** | - No code changes needed<br>- Works with any test<br>- More comprehensive instrumentation<br>- Easier to enable/disable | - Requires IntelliJ setup<br>- Agent JAR download needed<br>- Slightly slower test startup |
| **TraceUtils methods** | - Code-based (version controlled)<br>- Test-specific control<br>- No external dependencies | - Requires code changes<br>- Must be added to each test |

## Troubleshooting

### Agent JAR Not Found
```
Error opening zip file or JAR manifest missing
```
**Solution**: Run `./gradlew :solr:modules:opentelemetry:downloadOtelAgent`

### Connection Refused to localhost:4317
```
Failed to export spans. Server is UNAVAILABLE
```
**Solution**: Ensure Jaeger (or your tracing backend) is running and accessible

### No Traces Appearing in Jaeger
**Check:**
1. Service name matches (default: "solr-test")
2. Time range in Jaeger UI includes your test run time
3. Agent is properly attached (check test output for OTEL initialization messages)

### Debugging the Agent
Add to VM options:
```
-Dotel.javaagent.debug=true
```

This will print detailed agent initialization and instrumentation information.

## Example Screenshots

### IntelliJ Run Configuration with Java Agent
![IntelliJ Configuration](docs/intellij-otel-agent-config.png)

### Traces in Jaeger UI
![Jaeger Traces](docs/jaeger-traces-example.png)

## See Also

- [TraceUtils API Documentation](../../core/src/java/org/apache/solr/util/tracing/TraceUtils.java) - For programmatic tracing setup
- [OpenTelemetry Java Agent Documentation](https://opentelemetry.io/docs/languages/java/automatic/)
- [Jaeger Documentation](https://www.jaegertracing.io/docs/)
