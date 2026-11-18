package datadog.trace.instrumentation.azure.cosmos;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.azure.cosmos.implementation.RxDocumentServiceResponse;
import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;

class TracingCosmosSubscriberTest extends AbstractInstrumentationTest {

  @Test
  void forwardsSubscriptionUnderParentAndDemandUnderRequestSpan() {
    AtomicLong onSubscribeSpan = new AtomicLong();
    AtomicLong demandSpan = new AtomicLong();
    Subscription transport =
        new Subscription() {
          @Override
          public void request(long n) {
            demandSpan.set(activeSpanId());
          }

          @Override
          public void cancel() {}
        };
    CoreSubscriber<RxDocumentServiceResponse> downstream =
        new CoreSubscriber<RxDocumentServiceResponse>() {
          @Override
          public void onSubscribe(Subscription subscription) {
            onSubscribeSpan.set(activeSpanId());
            subscription.request(1);
          }

          @Override
          public void onNext(RxDocumentServiceResponse response) {}

          @Override
          public void onError(Throwable error) {}

          @Override
          public void onComplete() {}
        };

    AgentSpan parent = startSpan("test", "parent");
    AgentSpan request;
    try (ContextScope ignored = activateSpan(parent)) {
      Context parentContext = Context.current();
      request = startSpan("azure-cosmos", "cosmosdb.query");
      // TracingCosmosMono subscribes with the request span active
      try (ContextScope active = activateSpan(request)) {
        new TracingCosmosSubscriber(downstream, request, parentContext).onSubscribe(transport);
      }
    }

    assertEquals(parent.getSpanId(), onSubscribeSpan.get(), "span active in onSubscribe");
    assertEquals(request.getSpanId(), demandSpan.get(), "span active for upstream demand");

    request.finish();
    parent.finish();
  }

  private static long activeSpanId() {
    AgentSpan span = activeSpan();
    return span == null ? 0 : span.getSpanId();
  }
}
