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

The provider sends exposures from an OpenFeature provider hook. If you wrap it in OpenFeature's
`MultiProvider`, use `dev.openfeature:sdk` 1.23.0 or later: earlier versions do not run the hooks of
the providers inside a `MultiProvider`, so the provider sends no exposures there.

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

## Per-subject evaluation telemetry opt-out

Applications can keep flag evaluation enabled while excluding selected subjects from evaluation
telemetry. Configure the provider once, then pass the decision with each evaluation:

```java
import datadog.trace.api.openfeature.Provider;
import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.ImmutableContext;
import dev.openfeature.sdk.OpenFeatureAPI;
import dev.openfeature.sdk.Value;
import java.util.Collections;

OpenFeatureAPI api = OpenFeatureAPI.getInstance();
api.setProviderAndWait(new Provider(
    new Provider.Options().enableEvaluationTelemetryOptOutParameter(true)));
Client client = api.getClient();

// In a request handler, the application owns its consent and eligibility rules.
boolean optedOut = user.hasDeclinedEvaluationTelemetry();
boolean enabled = client.getBooleanValue("my-feature", false,
    new ImmutableContext(user.getId(), Collections.singletonMap(
        "dd_evaluation_telemetry_opt_out", new Value(optedOut))));
```

Use the per-evaluation context for concurrent requests. Do not replace the global context for each
subject. The key is exactly `dd_evaluation_telemetry_opt_out`, as in other Datadog SDKs.

Alternatively, enable support through deployment configuration and use `new Provider()`:

```shell
DD_FEATURE_FLAGS_EVALUATION_TELEMETRY_PER_SUBJECT_OPT_OUT_ENABLED=true
```

The equivalent JVM property is `-Ddd.feature.flags.evaluation.telemetry.per.subject.opt.out.enabled=true`.
The provider reads the normal Datadog configuration sources at construction. The default is `false`.
An explicit provider option overrides these sources, including an explicit `false`.
This setting only enables support for the subject attribute. It does not suppress telemetry for all subjects.

Only boolean `true` suppresses telemetry. Missing, false, null, and other types preserve telemetry.
The provider suppresses telemetry only when the option is enabled before provider construction.

The attribute remains available for targeting and exposure reporting with either activation setting.
Existing privacy and reporting settings apply. Targeting rules can use the attribute to select
assignments. Normal exposure deduplication applies when its value changes.

Opt-out suppresses new evaluation EVP events and counts, OpenTelemetry evaluation metrics, and
automatic flag-related span enrichment. It does not change flag values, evaluation details,
application defaults, experiment assignment, or exposure logging. Application hooks and unrelated
tracing remain active. Previously recorded or queued data is not removed.

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

The provider sends exposures to Datadog. It also puts its exposure decision and the selected split's
features into the flag metadata of every evaluation, so any OpenFeature hook can record exposures in
your own data store (see [Flag metadata](#flag-metadata)).

For convenience, `dd-openfeature` ships `ExposureHook`, which reads that metadata for you. It
receives every completed evaluation, with the same exposure decision that the provider uses for
Datadog.

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
- `shouldSend()` is true for a successful evaluation of an allocation that logs exposures, for a
  subject not yet exposed to the same allocation, variant and serial id. The serial id counts only
  with `dd-java-agent` 1.67.0 or later. These are exactly the exposures that the provider sends to
  Datadog, as long as Datadog logging is on and the provider's own hooks run: evaluate through an
  OpenFeature client, and inside a `MultiProvider` use OpenFeature SDK 1.23.0 or later. With an
  older SDK, a provider inside a `MultiProvider` sends no exposures, and `shouldSend()` is true for
  every exposure.
- `isExposure()` is true for every successful evaluation of an allocation that logs exposures,
  including repeats. `isCacheHit()` tells repeats apart.
- An evaluation that fails, including one that a later `after` hook fails, is not an exposure.
- An exception from the callback does not affect the evaluation. The first one is logged at warn
  level, later ones at debug level.

### Features

`getFeatures()` returns the features of the selected split whose destinations include `HOOK`.
Values are a `String`, `Double` or `Boolean`. Holdout splits carry:

| Feature | Value |
|---|---|
| `holdout.key` | The holdout key |
| `holdout.assignment_group` | `status_quo` or `all_shipped` |

The map is empty when the split has no such features. Datadog can add destinations later; the
provider ignores a destination it does not know and logs a warning once for each one.

### Flag metadata

The same data is in the evaluation's flag metadata, for hooks that use only the OpenFeature API.
Read it in the hook's `finallyAfter` stage, where the details match what the application receives,
and skip details that have an error code. `ExposureHook` does both.

| Key | Type | Meaning |
|---|---|---|
| `allocationKey` | String | The selected allocation |
| `__dd_do_log` | Boolean | Whether the allocation logs exposures. Present when the allocation logs exposures or span enrichment is on |
| `__dd_exposure_cache_hit` | Boolean | The subject was already exposed. Present only when `__dd_do_log` is true |
| `__dd_split_serial_id` | Integer | The split's serial id. Present when `__dd_do_log` is present, the split has a serial id, and the installed `dd-java-agent` supports serial ids |
| `__dd_feature.<key>` | String, Double or Boolean | One entry for each feature of the selected split that lists the `HOOK` destination |

### Agent versions

`dd-openfeature` and `dd-java-agent` ship separately. The provider turns a capability off when the
installed agent lacks it:

| Capability | Needs `dd-java-agent` | Without it |
|---|---|---|
| Serial ids on splits (`__dd_split_serial_id`) | 1.65.0 | The key is absent |
| Serial ids on exposures sent to Datadog | 1.67.0 | Exposures are sent without a serial id, so holdout attribution is lost |
| Flag evaluation events sent to Datadog | 1.66.0 | No flag evaluation events are sent |
| Split features (`getFeatures()`, `__dd_feature.<key>`) | 1.68.0 | `getFeatures()` is empty |

### Stop sending exposures to Datadog

```shell
DD_FEATURE_FLAGS_EXPOSURES_DATADOG_LOGGING_ENABLED=false
```

The provider then sends no exposures to Datadog. `ExposureHook` callbacks still receive every
evaluation, and `shouldSend()` gives the same result as before. The default is `true`.

This setting stops exposures only. Flag evaluation events have their own setting,
`DD_FLAGGING_EVALUATION_COUNTS_ENABLED`. The Java provider does not read dd-trace-py's
`DD_FFE_INTAKE_ENABLED`.

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
