<!--
  Licensed to the Apache Software Foundation (ASF) under one or more
  contributor license agreements.  See the NOTICE file distributed with
  this work for additional information regarding copyright ownership.
  The ASF licenses this file to You under the Apache License, Version 2.0
  (the "License"); you may not use this file except in compliance with
  the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
-->

Apache Solr Open Telemetry Tracer
=====================================

Introduction
------------
This module brings support for the new [OTEL](https://opentelemetry.io) (OpenTelemetry) standard,
and exposes a tracer configurator that can be enabled in the
`<tracerConfig>` tag of `solr.xml`. Please see Solr Reference Guide chapter "Distributed Tracing"
for details.

Enabling Tracing in Tests
--------------------------

There are two ways to enable distributed tracing in Solr tests:

### 1. Using the OpenTelemetry Java Agent (IntelliJ)

The Java agent provides automatic instrumentation without code changes. See [INTELLIJ_AGENT_SETUP.md](INTELLIJ_AGENT_SETUP.md) for detailed instructions.

Quick setup:
```bash
# Download the agent
./gradlew :solr:modules:opentelemetry:downloadOtelAgent

# Add to your IntelliJ run configuration VM options:
-javaagent:solr/modules/opentelemetry/build/agent/opentelemetry-javaagent.jar
-Dotel.javaagent.configuration-file=solr/modules/opentelemetry/otel-agent-config.properties
```

### 2. Using TraceUtils API (Programmatic)

Add tracing calls directly in test code for fine-grained control:

```java
@Override
public void setUp() throws Exception {
  TraceUtils.enableDistributedTracingForTests();
  super.setUp();
}

@Override
public void tearDown() throws Exception {
  super.tearDown();
  TraceUtils.disableDistributedTracingForTests();
}
```

See `org.apache.solr.util.tracing.TraceUtils` for more details.