# Datadog OpenFeature provider

`com.datadoghq:dd-openfeature` is the Datadog Feature Flags SDK for the [OpenFeature](https://openfeature.dev/) Java API.
It evaluates flags locally from the Universal Flag Configuration (UFC) and reports exposures and evaluation counts to Datadog.

The SDK works on its own. When the Datadog Java agent (`dd-java-agent`) is attached, the SDK also connects to it to use Remote Configuration, the Datadog Agent event proxy, agent telemetry, and APM span enrichment.

## Requirements

* Java 11 or later
* A Datadog [API key](https://docs.datadoghq.com/account_management/api-app-keys/#api-keys) and [site](https://docs.datadoghq.com/getting_started/site/) configured in the application for Datadog-managed CDN configuration or direct event intake.
  With `remote_config` and event delivery through a Datadog Agent, configure them on the Agent instead.
* Optionally, `dd-java-agent` 1.69.0 or later

## Setup

Add the dependency. It brings the OpenFeature SDK 1.23.0, the OpenTelemetry API and `jackson-core`:

```xml
<dependency>
  <groupId>com.datadoghq</groupId>
  <artifactId>dd-openfeature</artifactId>
  <version>${dd-openfeature.version}</version>
</dependency>
```

Register the provider:

```java
import com.datadog.openfeature.Provider;
import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.OpenFeatureAPI;

OpenFeatureAPI.getInstance().setProviderAndWait(new Provider());
Client client = OpenFeatureAPI.getInstance().getClient();
```

`new Provider(new Provider.Options().initTimeout(10, SECONDS))` changes how long initialization waits for the first flag configuration (30 seconds by default).

The provider sends exposures from an OpenFeature provider hook. If you wrap it in OpenFeature's `MultiProvider`, use `dev.openfeature:sdk` 1.23.0 or later: earlier versions do not run the hooks of the providers inside a `MultiProvider`, so the provider sends no exposures there.

## Configuration

Settings are read from the Datadog Java agent configuration when attached, then from `dd.`-prefixed system properties, then from environment variables.

| Environment variable | Default | Description |
|---|---|---|
| `DD_FEATURE_FLAGS_ENABLED` | `true` | Enables Feature Flags. `false` makes the provider fail initialization, so evaluations return defaults. |
| `DD_FEATURE_FLAGS_CONFIGURATION_SOURCE` | `agentless` | `agentless` polls the configuration from the Datadog CDN. `remote_config` receives it through Remote Configuration and requires `dd-java-agent`. |
| `DD_FEATURE_FLAGS_CONFIGURATION_SOURCE_AGENTLESS_BASE_URL` | | Custom configuration endpoint. A URL without a path gets the default path. |
| `DD_FEATURE_FLAGS_CONFIGURATION_SOURCE_AGENTLESS_POLL_INTERVAL_SECONDS` | `30` | CDN poll interval. |
| `DD_FEATURE_FLAGS_CONFIGURATION_SOURCE_AGENTLESS_REQUEST_TIMEOUT_SECONDS` | `5` | CDN request timeout. |
| `DD_FLAGGING_EVALUATION_COUNTS_ENABLED` | `true` | Sends aggregated flag evaluation counts. |
| `DD_FEATURE_FLAGS_EXPOSURES_DATADOG_LOGGING_ENABLED` | `true` | Sends exposures to Datadog. See [Stop sending exposures to Datadog](#stop-sending-exposures-to-datadog). |
| `DD_FEATURE_FLAGS_EXPERIMENTATION_OPT_OUT_MODE` | `disabled` | Controls per-subject experimentation opt-out: `disabled`, `immediate`, or `future_experiments_only`. See [Per-subject experimentation opt-out](#per-subject-experimentation-opt-out). |
| `DD_FEATURE_FLAGS_EVALUATION_TELEMETRY_PER_SUBJECT_OPT_OUT_ENABLED` | `false` | Lets the `dd_evaluation_telemetry_opt_out` context attribute opt subjects out of evaluation telemetry. See [Per-subject evaluation telemetry opt-out](#per-subject-evaluation-telemetry-opt-out). |
| `DD_EXPERIMENTAL_FLAGGING_PROVIDER_SPAN_ENRICHMENT_ENABLED` | `false` | Adds flag evaluations to the local root span. Requires `dd-java-agent`. |
| `DD_API_KEY`, `DD_SITE`, `DD_ENV`, `DD_SERVICE`, `DD_VERSION` | | Credentials and event context. |
| `DD_EXPERIMENTAL_FLAGGING_PROVIDER_ENABLED` | | Deprecated: `true` selects `remote_config`, `false` disables Feature Flags. An explicit source wins. |

## Features by setup

| Feature | SDK only | SDK + `dd-java-agent` |
|---|---|---|
| Configuration, `agentless` source | CDN | CDN |
| Configuration, `remote_config` source | Not supported | Remote Configuration |
| Exposures and evaluation counts | Direct intake, requires `DD_API_KEY` | Datadog Agent event proxy, with direct intake fallback in `agentless` mode |
| `feature_flag.evaluations` OpenTelemetry metric | Yes | Yes |
| Health metrics | No | Agent telemetry |
| Span enrichment | No | Yes |

## Exposures in your own data store

The provider sends exposures to Datadog. It also puts its exposure decision and the selected split's features into the flag metadata of every evaluation, so any OpenFeature hook can record exposures in your own data store (see [Flag metadata](#flag-metadata)).

For convenience, `dd-openfeature` ships `ExposureHook`, which reads that metadata for you. It receives every completed evaluation, with the same exposure decision that the provider uses for Datadog.

```java
import com.datadog.openfeature.ExposureHook;

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

* The callback runs on the evaluation thread. Do not write to the data store from the callback. Copy what you need and hand it to a background queue.
* `shouldSend()` is true for a successful evaluation of an allocation that logs exposures, for a subject not yet exposed to the same allocation, variant and serial id. These are exactly the exposures that the provider sends to Datadog, as long as Datadog logging is on and the provider's own hooks run: evaluate through an OpenFeature client, and inside a `MultiProvider` use OpenFeature SDK 1.23.0 or later. With an older SDK, a provider inside a `MultiProvider` sends no exposures, and `shouldSend()` is true for every exposure.
* `isExposure()` is true for every successful evaluation of an allocation that logs exposures, including repeats. `isCacheHit()` tells repeats apart.
* An evaluation that fails, including one that a later `after` hook fails, is not an exposure.
* An exception from the callback does not affect the evaluation. The first one is logged at warn level, later ones at debug level.

### Features

`getFeatures()` returns the features of the selected split whose destinations include `HOOK`. Values are a `String`, `Double` or `Boolean`. Holdout splits carry:

| Feature | Value |
|---|---|
| `holdout.key` | The holdout key |
| `holdout.assignment_group` | `status_quo` or `all_shipped` |

The map is empty when the split has no such features. Datadog can add destinations later; the provider ignores a destination it does not know and logs a warning once for each one.

### Flag metadata

The same data is in the evaluation's flag metadata, for hooks that use only the OpenFeature API. Read it in the hook's `finallyAfter` stage, where the details match what the application receives, and skip details that have an error code. `ExposureHook` does both.

| Key | Type | Meaning |
|---|---|---|
| `allocationKey` | String | The selected allocation |
| `__dd_do_log` | Boolean | Whether the allocation logs exposures. Present when the allocation logs exposures or span enrichment is on |
| `__dd_exposure_cache_hit` | Boolean | The subject was already exposed. Present only when `__dd_do_log` is true |
| `__dd_split_serial_id` | Integer | The split's serial id. Present when `__dd_do_log` is present and the split has a serial id |
| `__dd_feature.<key>` | String, Double or Boolean | One entry for each feature of the selected split that lists the `HOOK` destination |

### Stop sending exposures to Datadog

```shell
DD_FEATURE_FLAGS_EXPOSURES_DATADOG_LOGGING_ENABLED=false
```

The provider then sends no exposures to Datadog. `ExposureHook` callbacks still receive every evaluation, and `shouldSend()` gives the same result as before. The default is `true`.

This setting stops exposures only. Flag evaluation events have their own setting, `DD_FLAGGING_EVALUATION_COUNTS_ENABLED`. The Java provider does not read dd-trace-py's `DD_FFE_INTAKE_ENABLED`.

## Per-subject experimentation opt-out

Set `DD_FEATURE_FLAGS_EXPERIMENTATION_OPT_OUT_MODE=immediate` before constructing the provider.
The default is `disabled`. Existing configuration delivery and credentials are still required.

```java
import com.datadog.openfeature.Provider;
import dev.openfeature.sdk.MutableContext;
import dev.openfeature.sdk.OpenFeatureAPI;

OpenFeatureAPI api = OpenFeatureAPI.getInstance();
api.setProviderAndWait(new Provider());
boolean enabled = api.getClient().getBooleanValue("new-checkout", false,
    new MutableContext("subject-123")
        .add("dd_experimentation_opt_out_at", "2026-10-19T12:00:00Z"));
```

In immediate mode, a valid timestamp string excludes the subject from allocations with `doLog=true`.
This includes completed-experiment winners that still log exposures; omitted, null, and false `doLog` retain Java's ordinary-allocation behavior.
Evaluation continues through ordinary feature allocations. If none match, the provider returns the application's default.
A future timestamp also takes effect immediately. It does not schedule exclusion.

Omit the attribute for subjects who can participate. OpenFeature merges API, transaction, client, and invocation
contexts. Omitting it from an invocation does not remove an inherited value; remove it from the layer that owns it.
Setting it to null does not restore participation.

Invalid, null, empty, or non-string values return the application's default with `INVALID_CONTEXT` and a diagnostic.
This validation runs after configuration readiness and before flag lookup, so it also applies to ordinary and missing
flags. Before configuration is ready the result remains `PROVIDER_NOT_READY`. Disabled mode ignores the attribute,
including invalid values. Supply `Instant.toString()` as a string; passing an `Instant` object is invalid.

Java uses `DateTimeFormatter.ISO_OFFSET_DATE_TIME`, also used for allocation `startAt`, `endAt`, and activation.
It validates calendar dates, accepts up to nine fractional digits and offsets through ±18:00, and rejects leap
seconds, missing offsets, and surrounding whitespace. Its ISO syntax also accepts lowercase `t`/`z`, omitted
seconds, an empty fractional part, hour-only or second-bearing offsets, and signed extended years. This is Java's
existing grammar, not the browser SDK's timestamp grammar.

An explicit option overrides configuration:

```java
import com.datadog.openfeature.ExperimentationOptOutMode;

Provider provider = new Provider(new Provider.Options()
    .experimentationOptOutMode(ExperimentationOptOutMode.DISABLED));
```

`DEFAULT` reads the normal SDK configuration sources, including
`-Ddd.feature.flags.experimentation.opt.out.mode=immediate`.
The provider resolves the mode at construction; later changes to options or configuration do not change it.
Unsupported values throw `IllegalArgumentException` when Feature Flags is enabled. A disabled product skips this
unused setting's validation and retains its existing initialization behavior. Passing null to the option throws
`NullPointerException`.
The deprecated `datadog.trace.api.openfeature.Provider` alias uses the same evaluator
and accepts the same `com.datadog.openfeature.ExperimentationOptOutMode` options.

This setting is independent of evaluation telemetry opt-out.
Immediate withdrawal can change an enrolled subject's experience. Account for this change in experiment analysis.

Use `future_experiments_only` or `ExperimentationOptOutMode.FUTURE_EXPERIMENTS_ONLY` to exclude newer experiments.
Supply the same stored `dd_experimentation_opt_out_at` timestamp with each evaluation.
For exposure-logging allocations, the provider compares `randomizationActivatedAt` with that timestamp:

| Allocation activation | Behavior |
| --- | --- |
| Before the subject's opt-out time | Evaluate normally, including exposure logging |
| Equal to or after the opt-out time | Skip the allocation and continue ordinary delivery |
| Missing or null | Skip the allocation because its activation time is unknown |

Ordinary allocations remain available. Missing subject attributes preserve normal evaluation.
Both enabled modes reject invalid subject timestamps.
Malformed activation metadata on any allocation, including an ordinary, unreachable, or disabled flag's allocation,
returns `PARSE_ERROR` for the affected flag only in future mode with a valid subject timestamp.
Disabled mode, immediate mode, and evaluations without a subject timestamp preserve ordinary evaluation.
Invalid regex patterns and unconvertible numeric variant values are configuration errors regardless of opt-out:
the parser rejects only the affected flag, including when the malformed allocation would be skipped.
Java's existing regex syntax and numeric coercions remain supported.
Comparisons retain nanosecond precision and account for timezone offsets.
The cutoff does not depend on the SDK clock. It is not a scheduled change or proof of prior enrollment.
An older allocation can still assign a subject for the first time.

Enable future mode after the configuration service supplies stable activation timestamps and preserves ordinary fallback allocations.
Keep the subject's stored opt-out time stable across sessions and SDK instances.
The provider stores no consent history and emits no new event type. The opt-out attribute remains in the evaluation
context: existing exposure reports can include it, and evaluation events can include it when full evaluation data
reporting is enabled. Existing reporting controls and consent settings still apply.

## Per-subject evaluation telemetry opt-out

Applications can keep flag evaluation enabled while excluding selected subjects from evaluation
telemetry. Configure the provider once, then pass the decision with each evaluation:

```java
import com.datadog.openfeature.Provider;
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

## Migrating from earlier versions

* The provider moved to `com.datadog.openfeature.Provider`, and the exposure hook to `com.datadog.openfeature.ExposureHook`. `datadog.trace.api.openfeature.Provider` and `datadog.trace.api.openfeature.ExposureHook` are kept as deprecated aliases.
* The SDK no longer requires `dd-java-agent` and no longer links against agent classes.
* `dd-openfeature` releases before 1.69.0 link to agent classes that `dd-java-agent` 1.69.0 removes: with `dd-java-agent` 1.69.0 or later, they return the caller default values. Upgrade `dd-openfeature` to 1.69.0 or later together with `dd-java-agent`.

## Design notes for contributors

The SDK does not use any dd-trace-java internal module. Its parts that the agent can provide are behind the `com.datadog.openfeature.internal.bridge.v1` SPI:

* `AgentBridges.detect()` returns `AgentBridge.NONE` without the agent.
* The `:dd-java-agent:instrumentation:datadog:openfeature:dd-openfeature-1.69` instrumentation replaces its result with a bridge delegating to the agent backend (`products/feature-flagging/feature-flagging-lib`) through the bootstrap `FeatureFlaggingGateway`.
* The `:dd-java-agent:instrumentation:datadog:openfeature:dd-openfeature-1.56` instrumentation keeps the providers released before the bridge (1.56 to 1.68) from linking to the agent bootstrap API they used: they return the caller defaults.
* The SPI is the only contract crossing SDK and agent versions, and the instrumentation references to it are checked by muzzle. Once released, an SPI version is frozen: only `default` methods can be added. A breaking change goes to a new package (`bridge.v2`), with its own agent instrumentation, and the SDK keeps the previous version for the agents that only implement it. The `japicmpBridge` tasks check each released SPI version against the first SDK release that shipped it.
* UFC parsing, evaluation, event payloads and batching stay in the SDK. The agent forwards raw Remote Configuration documents and event payloads.
* The public API is everything outside the `internal` packages. The `japicmpPublicApi` task checks it stays binary and source compatible with the previous release, the latest one before the version being built, so each release keeps the API of all the previous ones. Exclude an intentional break explicitly.
