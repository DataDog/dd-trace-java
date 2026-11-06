package datadog.trace.instrumentation.azure.cosmos;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.instrumentation.azure.cosmos.AzureCosmosDecorator.DECORATE;

import com.azure.cosmos.implementation.RxDocumentServiceRequest;
import com.azure.cosmos.implementation.RxDocumentServiceResponse;
import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Mono;

/**
 * Starts a span for one Cosmos request each time the request is subscribed to, and keeps it active
 * while the SDK subscribes to the transport, so that the transport work is parented to it. The span
 * is finished by {@link TracingCosmosSubscriber} on response, error or cancellation.
 */
public final class TracingCosmosMono extends Mono<RxDocumentServiceResponse> {
  private final Mono<RxDocumentServiceResponse> source;
  private final RxDocumentServiceRequest request;
  private final CosmosClientInfo clientInfo;

  public TracingCosmosMono(
      Mono<RxDocumentServiceResponse> source,
      RxDocumentServiceRequest request,
      CosmosClientInfo clientInfo) {
    this.source = source;
    this.request = request;
    this.clientInfo = clientInfo;
  }

  @Override
  public void subscribe(CoreSubscriber<? super RxDocumentServiceResponse> actual) {
    Context parentContext = Context.current();
    AgentSpan span;
    try {
      span = DECORATE.startRequestSpan(request, clientInfo);
    } catch (Throwable t) {
      // never let tracing break the request
      source.subscribe(actual);
      return;
    }
    try (ContextScope ignored = activateSpan(span)) {
      source.subscribe(new TracingCosmosSubscriber(actual, span, parentContext));
    }
  }
}
