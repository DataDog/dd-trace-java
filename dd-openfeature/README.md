# Datadog OpenFeature provider

`com.datadoghq:dd-openfeature` is the Datadog Feature Flags SDK for the [OpenFeature](https://openfeature.dev/) Java API.
It evaluates flags locally from the Universal Flag Configuration (UFC) and reports exposures and evaluation counts to Datadog.

The SDK works on its own. When the Datadog Java agent (`dd-java-agent`) is attached, the SDK also connects to it to use Remote Configuration, the Datadog Agent event proxy, agent telemetry, and APM span enrichment.

## Requirements

* Java 11 or later
* A Datadog [API key](https://docs.datadoghq.com/account_management/api-app-keys/#api-keys) and [site](https://docs.datadoghq.com/getting_started/site/), unless events go through a Datadog Agent
* Optionally, `dd-java-agent` 1.67.0 or later

## Setup

Add the dependency. It brings the OpenFeature SDK, the OpenTelemetry API and `jackson-core`:

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
import dev.openfeature.sdk.OpenFeatureAPI;

OpenFeatureAPI.getInstance().setProviderAndWait(new Provider());
Client client = OpenFeatureAPI.getInstance().getClient();
```

`new Provider(new Provider.Options().initTimeout(10, SECONDS))` changes how long initialization waits for the first flag configuration (30 seconds by default).

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

## Migrating from earlier versions

* The provider moved to `com.datadog.openfeature.Provider`. `datadog.trace.api.openfeature.Provider` is kept as a deprecated alias.
* The SDK no longer requires `dd-java-agent` and no longer links against agent classes.
* `dd-openfeature` 1.66.0 and earlier require the agent classes this release removes. Upgrade `dd-openfeature` together with `dd-java-agent` 1.67.0 or later.

## Design notes for contributors

The SDK does not use any dd-trace-java internal module. Its parts that the agent can provide are behind the `com.datadog.openfeature.internal.connector` SPI:

* `Connectors.detect()` returns `Connector.NONE` without the agent.
* The `:dd-java-agent:instrumentation:datadog:openfeature:dd-openfeature-connector` instrumentation replaces its result with a connector delegating to the agent backend (`products/feature-flagging/feature-flagging-lib`) through the bootstrap `FeatureFlaggingGateway`.
* The SPI is the only contract crossing SDK and agent versions. It must evolve in a backward compatible way, and the instrumentation references to it are checked by muzzle.
* UFC parsing, evaluation, event payloads and batching stay in the SDK. The agent forwards raw Remote Configuration documents and event payloads.
