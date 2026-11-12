# Azure Durable Functions Java tracing

For correlated host and Java worker traces, enable OpenTelemetry in the Function App's
`host.json`:

```json
{
  "version": "2.0",
  "telemetryMode": "OpenTelemetry"
}
```

The instrumentation honors the incoming W3C `traceparent` sampled flag and Datadog
`tracestate` sampling decision. It does not force-keep Durable invocations. If the host or an
upstream service sends a drop decision, the corresponding Durable spans may not be ingested.
Keep the application's sampling configuration consistent across the HTTP starter and Durable
worker invocations; enabling OpenTelemetry does not override an intentional upstream drop.

See the [Azure Functions OpenTelemetry setup guide](https://learn.microsoft.com/en-us/azure/azure-functions/opentelemetry-howto)
for host configuration details.
