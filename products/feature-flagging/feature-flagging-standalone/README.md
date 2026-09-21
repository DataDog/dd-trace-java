# Standalone Datadog OpenFeature provider

This migration POC publishes the customer artifact as `com.datadoghq:dd-openfeature`.
The public entry point remains `datadog.trace.api.openfeature.Provider`.
Java 11 or later is required.

## No Java agent

Add `dd-openfeature`, then register the provider:

```java
OpenFeatureAPI.getInstance().setProviderAndWait(new Provider());
```

Set `DD_API_KEY`, `DD_SITE`, and `DD_ENV` for managed configuration and direct product events.
Manual registration starts direct configuration by default.
`DD_FEATURE_FLAGS_ENABLED=false` prevents startup.
Loading the library without registration does not start polling.
The same path works with the OTel Java agent as the only Java agent.

The artifact declares normal OpenFeature and OTel API dependencies.
It does not install an OTel SDK, exporter, collector, or global provider.
The POC uses OTel API 1.57.0 to permit application SDK registration after provider construction.
The Java Language Tools team must approve this dependency contract before release.

Exposures and aggregated flag evaluations use direct EVP delivery.
They do not depend on OTel.
The optional `feature_flag.evaluations` metric uses the application's OTel configuration.

## Java agent and injection

The agent distribution uses the same evaluator and shared event pipelines.
An OpenFeature-only application can use provider injection after explicit Feature Flags activation.
Set `DD_FEATURE_FLAGS_ENABLED=true` to enable injection.
Set `DD_FEATURE_FLAGS_CONFIGURATION_SOURCE=agentless` for direct configuration.
Injection also works when `DD_TRACE_ENABLED=false`.
`DD_FEATURE_FLAGS_ENABLED=false` disables injection and runtime startup from manual registration.
Tracing integration switches do not control provider installation. There is no injection-only switch.
Injection preserves an application-selected provider.

For Remote Configuration, set `DD_FEATURE_FLAGS_CONFIGURATION_SOURCE=remote_config`.
This mode requires `dd-java-agent` and a compatible local Datadog Agent.
It uses the agent's RC client and EVP proxy. It never falls back to direct configuration.
The local Datadog Agent holds the API key.

Manual `-javaagent` attachment tests provider injection, not platform SSI deployment.
Actual SSI attachment and rollback remain a separate certification gate.

## Compatibility and deprecation

Use `DD_FEATURE_FLAGS_ENABLED` for provider enablement. The legacy provider setting remains supported.
Keep `DD_EXPERIMENTAL_FLAGGING_PROVIDER_SPAN_ENRICHMENT_ENABLED` for span enrichment, off by default.
Legacy provider activation continues to select RC.
RC and manual registration are not deprecated.
Bootstrap bridge payloads remain unshaded for mixed installations.
No compatibility shim is removed before the Java team defines its support window.

## Build

```sh
./gradlew :products:feature-flagging:feature-flagging-standalone:shadowJar \
  :products:feature-flagging:feature-flagging-standalone:generatePomFileForMavenPublication \
  :dd-java-agent:shadowJar
```

The API module is internal and unbundled. The standalone module owns Maven publication.
`feature-flagging-lib` contains evaluation, configuration, lifecycle, events, and direct HTTP.
Its evaluator-only artifact serves the API and injection without including the full runtime.
The standalone assembly consumes the full library. The agent assembly does not depend on standalone publication.
The repository's shadow publication uses POM metadata, not Gradle module metadata.
