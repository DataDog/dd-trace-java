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

## Remote Configuration, with or without a Java agent

Set `DD_FEATURE_FLAGS_CONFIGURATION_SOURCE=remote_config` and `DD_REMOTE_CONFIGURATION_ENABLED=true`.
Set `DD_TRACE_AGENT_URL` to a compatible Datadog Agent service, for example `http://localhost:8126`.
The Datadog Agent holds the API key. The application does not need one.
Without `dd-java-agent`, manual provider registration starts the standalone RC client and EVP proxy delivery.
For that path, add `com.datadoghq:dd-openfeature-remote-config` at the same version as `dd-openfeature`.
The base JAR and its published dependencies contain no RC client. The optional artifact supplies it.
This also works with the OTel Java agent as the only Java agent. No OTel collector is required.
With a compatible `dd-java-agent`, the provider uses the agent runtime instead. It does not start a second poller.
That path does not need the RC add-on. Merely adding the artifact does not enable RC.
Selecting RC without either implementation produces a clear initialization error.

Explicit RC never falls back to CDN configuration or direct product-event delivery.
If RC is unavailable before initial configuration, evaluations use caller defaults.
If RC becomes unavailable after initial configuration, evaluations continue to use cached configuration.
Disabling Remote Configuration while selecting `remote_config` prevents standalone startup.
Installation and configuration delivery are separate choices. Standalone does not mean direct delivery only.

## Java agent and injection

The agent distribution uses the same evaluator and shared event pipelines.
An OpenFeature-only application can use provider injection after explicit Feature Flags activation.
Set `DD_FEATURE_FLAGS_ENABLED=true` to enable injection.
Set `DD_FEATURE_FLAGS_CONFIGURATION_SOURCE=agentless` for direct configuration.
Injection also works when `DD_TRACE_ENABLED=false`.
`DD_FEATURE_FLAGS_ENABLED=false` disables injection and runtime startup from manual registration.
Tracing integration switches do not control provider installation. There is no injection-only switch.
Injection preserves an application-selected provider.

Injection supports either configuration source. RC uses the Datadog Agent service described above.

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
  :products:feature-flagging:feature-flagging-remote-config:shadowJar \
  :products:feature-flagging:feature-flagging-remote-config:generatePomFileForMavenPublication \
  :dd-java-agent:shadowJar
```

The API module is internal and unbundled. The standalone module owns Maven publication.
`feature-flagging-lib` contains evaluation, CDN delivery, lifecycle, events, and HTTP.
`feature-flagging-remote-config` is an optional assembly, not another evaluator or RC protocol implementation.
Its internal interface accepts configuration bytes and serialized events, with no RC or HTTP types.
Its evaluator-only artifact serves the API and injection without including the full runtime.
The standalone assembly consumes the full library. The agent assembly does not depend on standalone publication.
The repository's shadow publication uses POM metadata, not Gradle module metadata.
