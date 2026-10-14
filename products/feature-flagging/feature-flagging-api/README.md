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

## Exposure hooks (draft API)

The provider registers an internal Datadog exposure logging hook by default. It uses the existing
agent exposure writer and transport. Set `DD_FEATURE_FLAGS_EXPOSURES_DATADOG_LOGGING_ENABLED=false`
(or `-Ddd.feature.flags.exposures.datadog.logging.enabled=false`) before constructing the provider to
disable that hook. Evaluation metrics, evaluation logging and span enrichment have separate controls.

Customers can explicitly register the public `ExposureHook` at the OpenFeature client, API or
invocation level. Customer registration is independent of the Datadog logging setting:

```java
import datadog.trace.api.openfeature.ExposureHook;

client.addHooks(new ExposureHook(evaluation -> {
    if (evaluation.shouldSend()) {
        // Example only: copy the fields needed by your destination into its asynchronous queue.
        System.out.printf("Exposure: flag=%s variant=%s subject=%s serialId=%s%n",
            evaluation.getDetails().getFlagKey(),
            evaluation.getDetails().getVariant(),
            evaluation.getContext().getTargetingKey(),
            evaluation.getDetails().getFlagMetadata().getInteger("__dd_split_serial_id"));
    }
}));
```

The callback runs synchronously at OpenFeature's `finallyAfter` stage for every evaluation,
including errors, non-exposures and repeats. Callback exceptions do not change the flag result.
Copy fields before handing work to a background queue; the OpenFeature context may be caller-owned.

- `isExposure()` means a successful result came from an allocation with `doLog=true`.
  It cannot confirm that the application used the result or a person saw the feature.
- `getCacheHit()` is `false` for a new or changed assignment, `true` for an unchanged assignment,
  and `null` when not applicable or unavailable. Every hook sees the same advisory.
- `shouldSend()` recommends exposure candidates with a cache miss. Customers may ignore this
  recommendation, for example by sending all results with `isExposure() == true`.
- Standard OpenFeature hooks can read the same metadata directly: `__dd_do_log`,
  `__dd_exposure_cache_hit`, `allocationKey`, `__dd_eval_timestamp_ms`, and optional
  `__dd_split_serial_id`. These names and the helper API are proposed by this draft.

The advisory cache belongs to one provider and retains up to 1,024 `(flag, targeting key)` entries
using LRU eviction. Each entry records the last `(allocation, variant, split serial ID)` assignment;
assignment changes, including returning to an earlier assignment, are misses. Concurrent observations
are atomic. Eviction or a new provider allows another miss; there is no time-based expiry.

This cache records **observations, not delivery acknowledgements**. It advances during resolution,
even with Datadog logging disabled, no customer hook, or a failed customer callback. Late hook
registration does not replay assignments. If an application `after` hook rejects a result, final
hooks see an error and emit no exposure, but the observation remains cached. Destinations that need
retries or different deduplication should own that policy and ignore the advisory as needed.
Datadog's existing writer retains its own cache and best-effort transport behavior.

Split serial IDs remain available without span enrichment. Older agents without split serial IDs or
the newer exposure constructor keep their existing compatibility fallback. This draft does not add
new holdout fields or a public transport API.

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
