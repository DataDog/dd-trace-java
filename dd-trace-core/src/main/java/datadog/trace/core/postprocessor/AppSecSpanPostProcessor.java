package datadog.trace.core.postprocessor;

import static datadog.trace.api.gateway.Events.EVENTS;

import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.core.DDSpan;
import datadog.trace.core.DDSpanContext;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public class AppSecSpanPostProcessor implements SpanPostProcessor {

  // Extract this to allow for easier testing
  protected AgentTracer.TracerAPI tracer() {
    return AgentTracer.get();
  }

  @Override
  public boolean process(DDSpan span, BooleanSupplier timeoutCheck) {
    DDSpanContext context = span.context();
    if (context == null) {
      return false;
    }

    CallbackProvider cbp = tracer().getCallbackProvider(RequestContextSlot.APPSEC);
    if (cbp == null) {
      return false;
    }

    RequestContext ctx = span.getRequestContext();
    if (ctx == null) {
      return false;
    }

    Consumer<RequestContext> postProcessingCallback = cbp.getCallback(EVENTS.postProcessing());
    if (postProcessingCallback == null) {
      return false;
    }

    postProcessingCallback.accept(ctx);
    return true;
  }
}
