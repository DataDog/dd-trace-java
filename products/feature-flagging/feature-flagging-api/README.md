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

To enable evaluation metrics (`feature_flag.evaluations` counter), add the OpenTelemetry SDK dependencies:

```xml
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-sdk-metrics</artifactId>
    <version>1.47.0</version>
</dependency>
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-exporter-otlp</artifactId>
    <version>1.47.0</version>
</dependency>
```

Any OpenTelemetry API 1.x version is compatible. If these dependencies are absent, the provider operates normally without metrics.

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

When the OTel SDK dependencies are on the classpath, the provider records a `feature_flag.evaluations` counter via OTLP HTTP/protobuf. Metrics are exported every 10 seconds to the Datadog Agent's OTLP receiver.

### Configuration

| Environment variable | Description | Default |
|---|---|---|
| `OTEL_EXPORTER_OTLP_METRICS_ENDPOINT` | Signal-specific OTLP endpoint (used as-is) | — |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | Generic OTLP endpoint (`/v1/metrics` appended) | — |
| (none set) | Default endpoint | `http://localhost:4318/v1/metrics` |

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
- Datadog Agent with Remote Configuration enabled
- `DD_EXPERIMENTAL_FLAGGING_PROVIDER_ENABLED=true`
