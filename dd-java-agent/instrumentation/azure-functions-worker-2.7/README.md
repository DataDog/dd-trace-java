# Azure Functions Java worker tracing

This module traces Durable Functions and Cosmos DB trigger invocations in the Java worker.

## Durable Functions

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

## Cosmos DB trigger

Functions with a `@CosmosDBTrigger` parameter get an `azure.functions.invoke` span with resource
`CosmosDB <function name>` and the `aas.function.trigger:CosmosDB` tag. The span starts a new
trace instead of continuing the trace context the Functions host passes to the worker, so the
host's sampled flag doesn't decide whether the span is kept.
