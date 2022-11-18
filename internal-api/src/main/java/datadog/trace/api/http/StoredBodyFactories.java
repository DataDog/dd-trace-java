package datadog.trace.api.http;

import static datadog.trace.api.gateway.Events.EVENTS;

import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.nio.charset.Charset;
import java.util.function.BiFunction;
import java.util.function.Supplier;

public class StoredBodyFactories {
  private StoredBodyFactories() {}

  public static StoredByteBody maybeCreateForByte(Charset charset, Object contentLengthHeader) {
    AgentSpan agentSpan = AgentTracer.activeSpan();
    if (agentSpan == null) {
      return null;
    }
    return maybeCreateForByte(charset, agentSpan, contentLengthHeader);
  }

  @SuppressWarnings("Duplicates")
  public static StoredByteBody maybeCreateForByte(
      Charset charset, AgentSpan agentSpan, Object contentLengthHeader) {
    RequestContext requestContext = agentSpan.getRequestContext();
    if (requestContext == null) {
      return null;
    }

    CallbackProvider cbp = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC);
    BiFunction<RequestContext, StoredBodySupplier, Void> requestStartCb =
        cbp.getCallback(EVENTS.requestBodyStart());
    BiFunction<RequestContext, StoredBodySupplier, Flow<Void>> requestEndedCb =
        cbp.getCallback(EVENTS.requestBodyDone());
    if (requestStartCb == null || requestEndedCb == null) {
      return null;
    }

    int lengthHint = parseLengthHeader(contentLengthHeader);

    return new StoredByteBody(requestContext, requestStartCb, requestEndedCb, charset, lengthHint);
  }

  @SuppressWarnings("Duplicates")
  public static Flow<Void> maybeDeliverBodyInOneGo(
      Supplier<CharSequence> supplier, RequestContext requestContext) {
    CallbackProvider cbp = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC);
    BiFunction<RequestContext, StoredBodySupplier, Void> requestStartCb =
        cbp.getCallback(EVENTS.requestBodyStart());
    BiFunction<RequestContext, StoredBodySupplier, Flow<Void>> requestEndedCb =
        cbp.getCallback(EVENTS.requestBodyDone());
    if (requestStartCb == null || requestEndedCb == null) {
      return Flow.ResultFlow.empty();
    }

    StoredBodySupplier wrappedSupplier = new ConstantBodySupplier(supplier);
    requestStartCb.apply(requestContext, wrappedSupplier);
    return requestEndedCb.apply(requestContext, wrappedSupplier);
  }

  public static Flow<Void> maybeDeliverBodyInOneGo(final String str, RequestContext reqCtx) {
    return maybeDeliverBodyInOneGo(() -> str, reqCtx);
  }

  public static class ConstantBodySupplier implements StoredBodySupplier {
    private final CharSequence sequence;

    public ConstantBodySupplier(Supplier<CharSequence> original) {
      this.sequence = original.get();
    }

    @Override
    public CharSequence get() {
      return this.sequence;
    }
  }

  public static StoredCharBody maybeCreateForChar(Object contentLengthHeader) {
    AgentSpan agentSpan = AgentTracer.activeSpan();
    if (agentSpan == null) {
      return null;
    }
    return maybeCreateForChar(agentSpan, contentLengthHeader);
  }

  @SuppressWarnings("Duplicates")
  public static StoredCharBody maybeCreateForChar(AgentSpan agentSpan, Object contentLengthHeader) {
    RequestContext requestContext = agentSpan.getRequestContext();
    if (requestContext == null) {
      return null;
    }

    CallbackProvider cbp = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC);
    BiFunction<RequestContext, StoredBodySupplier, Void> requestStartCb =
        cbp.getCallback(EVENTS.requestBodyStart());
    BiFunction<RequestContext, StoredBodySupplier, Flow<Void>> requestEndedCb =
        cbp.getCallback(EVENTS.requestBodyDone());
    if (requestStartCb == null || requestEndedCb == null) {
      return null;
    }

    int lengthHint = parseLengthHeader(contentLengthHeader);

    return new StoredCharBody(requestContext, requestStartCb, requestEndedCb, lengthHint);
  }

  private static int parseLengthHeader(Object contentLengthHeader) {
    if (contentLengthHeader instanceof Number) {
      return ((Number) contentLengthHeader).intValue();
    }
    if (contentLengthHeader != null) {
      try {
        return Integer.parseInt(contentLengthHeader.toString());
      } catch (NumberFormatException nfe) {
        // purposefully left blank
      }
    }
    return 0;
  }
}
