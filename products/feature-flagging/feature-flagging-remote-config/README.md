# Optional Remote Configuration transport

This POC publishes `com.datadoghq:dd-openfeature-remote-config`.
Use the same version as `dd-openfeature`. The add-on POM includes the base provider dependency.

Add this artifact only when the application selects Remote Configuration without a compatible Datadog Java agent.
Register the normal Datadog OpenFeature provider. Then set:

```sh
DD_FEATURE_FLAGS_CONFIGURATION_SOURCE=remote_config
DD_REMOTE_CONFIGURATION_ENABLED=true
DD_TRACE_AGENT_URL=http://localhost:8126
```

The Datadog Agent service holds the API key. The application needs neither an API key nor an OTel collector.
The OTel Java agent can remain the application's only Java agent.
Adding this artifact alone does not activate RC. Manual registration still defaults to CDN delivery.

The add-on reuses the repository's existing RC client. It delivers raw configuration bytes to the base provider's parser.
It sends serialized product events through the Datadog Agent's EVP proxy. It never falls back to direct intake.
The base JAR owns evaluation, queues, configuration parsing, and lifecycle. This artifact contains no provider or evaluator.
The Java agent keeps its existing RC implementation and has no build or publication dependency on this artifact.

The internal `RemoteConfigTransport` interface must stay version-aligned with the base provider.
RC protocol, cryptography, and HTTP implementation types do not cross that boundary.
The default JAR's POM must not depend on this add-on.

Validation uses HTTP over TCP. Unix sockets, Windows named pipes, and native dependency isolation need separate release validation.
