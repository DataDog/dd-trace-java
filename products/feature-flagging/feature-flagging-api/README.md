# dd-openfeature

Datadog OpenFeature Provider for Java. Implements the [OpenFeature](https://openfeature.dev/) `FeatureProvider` interface for Datadog's Feature Flags and Experimentation (FFE) product.

Published as `com.datadoghq:dd-openfeature` on Maven Central.

## Setup

```xml
<dependency>
    <groupId>com.datadoghq</groupId>
    <artifactId>dd-openfeature</artifactId>
    <version>${dd-openfeature.version}</version>
</dependency>
```

The OpenFeature SDK (`dev.openfeature:sdk`) is included as a transitive dependency.

### Evaluation metrics (optional)

To enable evaluation metrics (`feature_flag.evaluations` counter), enable the Datadog Java agent's
OpenTelemetry metrics pipeline:

```shell
DD_METRICS_OTEL_ENABLED=true
```

The provider records metrics through the OpenTelemetry Metrics API. Add `opentelemetry-api` if your
application does not already use the OpenTelemetry API for custom metrics:

```xml
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-api</artifactId>
    <version>1.47.0</version>
</dependency>
```

The OpenTelemetry SDK and OTLP exporter are not required on the application classpath. The Datadog
Java agent collects the API metric and exports it through the same OTLP pipeline as other custom OTel
metrics.

## Usage

```java
import datadog.trace.api.openfeature.Provider;
import dev.openfeature.sdk.OpenFeatureAPI;
import dev.openfeature.sdk.Client;

OpenFeatureAPI api = OpenFeatureAPI.getInstance();
api.setProviderAndWait(new Provider());
Client client = api.getClient();

boolean enabled = client.getBooleanValue("my-feature", false,
    new MutableContext("user-123"));
```

## Evaluation metrics

When `DD_METRICS_OTEL_ENABLED=true` and the OpenTelemetry API is on the classpath, the provider
records a `feature_flag.evaluations` counter. The Datadog Java agent exports it to the Datadog
Agent's OTLP receiver using the configured OpenTelemetry metrics export interval.

### Configuration

Configure the OTLP endpoint and protocol using the standard Datadog Java agent OpenTelemetry metrics
settings. For example, to export metrics over OTLP/gRPC:

```shell
DD_METRICS_OTEL_ENABLED=true
OTEL_EXPORTER_OTLP_ENDPOINT=http://<agent-host>:4317
OTEL_EXPORTER_OTLP_PROTOCOL=grpc
```

### Metric attributes

| Attribute | Description |
|---|---|
| `feature_flag.key` | Flag key |
| `feature_flag.result.variant` | Resolved variant key |
| `feature_flag.result.reason` | Evaluation reason (lowercased) |
| `error.type` | Error code (lowercased, only on error) |
| `feature_flag.result.allocation_key` | Allocation key (when present) |

## Requirements

- Java 11+
- `DD_FEATURE_FLAGS_CONFIGURATION_SOURCE=agentless` uses the Datadog agentless
  backend. `remote_config` uses the existing Agent Remote Configuration path.
  `offline` is reserved for startup-provided UFC bytes; until those bytes are
  implemented, no network source starts and evaluations use defaults.
