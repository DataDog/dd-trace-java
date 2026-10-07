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

## Exposures in your own data store

The provider sends exposures to Datadog. To also record them in your own data store, register an
`ExposureHook`. It receives every completed evaluation, with the same exposure decision that the
provider uses for Datadog.

```java
import datadog.trace.api.openfeature.ExposureHook;

BlockingQueue<ExposureRecord> queue = new LinkedBlockingQueue<>(10_000);

OpenFeatureAPI.getInstance().addHooks(new ExposureHook(evaluation -> {
  if (!evaluation.shouldSend()) {
    return;
  }
  Map<String, Object> features = evaluation.getFeatures();
  queue.offer(new ExposureRecord(
      evaluation.getDetails().getFlagKey(),
      evaluation.getDetails().getVariant(),
      evaluation.getContext().getTargetingKey(),
      (String) features.get("holdout.key"),
      (String) features.get("holdout.assignment_group")));
}));
// A background thread drains the queue and writes to the data store.
```

- The callback runs on the evaluation thread. Do not write to the data store from the callback.
  Copy what you need and hand it to a background queue.
- `shouldSend()` is true for exactly the exposures that the provider sends to Datadog: a successful
  evaluation of an allocation that logs exposures, for a subject not yet exposed to the same
  allocation, variant and serial id.
- `isExposure()` is true for every successful evaluation of an allocation that logs exposures,
  including repeats. `getCacheHit()` tells repeats apart.
- An evaluation that fails, including one that a later `after` hook fails, is not an exposure.
- An exception from the callback is logged and does not affect the evaluation.

### Features

`getFeatures()` returns the features of the selected split. Values are a `String`, `Double` or
`Boolean`. Holdout splits carry:

| Feature | Value |
|---|---|
| `holdout.key` | The holdout key |
| `holdout.assignment_group` | `status_quo` or `all_shipped` |

The map is empty when the split has no features.

### Flag metadata

The same data is in the evaluation's flag metadata, for hooks that use only the OpenFeature API:

| Key | Type | Meaning |
|---|---|---|
| `allocationKey` | String | The selected allocation |
| `__dd_do_log` | Boolean | The allocation logs exposures |
| `__dd_exposure_cache_hit` | Boolean | The subject was already exposed. Present only when `__dd_do_log` is true |
| `__dd_split_serial_id` | Integer | The split's serial id. Present when the split has one and `__dd_do_log` is true |
| `__dd_feature.<key>` | String, Double or Boolean | One entry for each feature of the selected split |

### Stop sending exposures to Datadog

```shell
DD_FEATURE_FLAGS_EXPOSURES_DATADOG_LOGGING_ENABLED=false
```

The provider then sends no exposures to Datadog. `ExposureHook` callbacks still receive every
evaluation, and `shouldSend()` gives the same result as before. The default is `true`.

## Requirements

- Java 11+
- `DD_FEATURE_FLAGS_CONFIGURATION_SOURCE=agentless` uses the Datadog agentless
  backend. Set `DD_FEATURE_FLAGS_CONFIGURATION_SOURCE_AGENTLESS_BASE_URL` to a
  different HTTP backend while keeping agentless delivery semantics. A bare
  host uses the standard rules-based server path; a URL with a path is used as
  the exact UFC endpoint. Configured URLs are opaque: the SDK does not add the
  Datadog-managed `dd_env` query parameter, so custom backends must include any
  required tenant or environment scope in the configured URL. The derived
  Datadog-managed endpoint is
  `https://ufc-server.ff-cdn.<site>/api/v2/feature-flagging/config/rules-based/server`
  and expects UFC under the JSON:API `data.attributes` response member. It is
  intended for supported commercial sites; use an explicit base URL elsewhere.
  Agentless responses do not have an SDK-imposed payload-size limit.
  `remote_config` uses the existing Agent Remote
  Configuration path. `offline` is reserved for startup-provided UFC bytes;
  until those bytes are implemented, no network source starts and evaluations
  use defaults.
