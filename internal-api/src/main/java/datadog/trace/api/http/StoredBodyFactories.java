package datadog.trace.api.http;

import datadog.trace.api.function.BiFunction;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Events;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.nio.charset.Charset;

public class StoredBodyFactories {
  private StoredBodyFactories() {}

  public static StoredByteBody maybeCreateForByte(Charset charset, String contentLengthHeader) {
    AgentSpan agentSpan = AgentTracer.activeSpan();
    if (agentSpan == null) {
      return null;
    }
    return maybeCreateForByte(charset, agentSpan, contentLengthHeader);
  }

  @SuppressWarnings("Duplicates")
  public static StoredByteBody maybeCreateForByte(
      Charset charset, AgentSpan agentSpan, String contentLengthHeader) {
    RequestContext requestContext = agentSpan.getRequestContext();
    if (requestContext == null) {
      return null;
    }

    CallbackProvider cbp = AgentTracer.get().instrumentationGateway();
    BiFunction<RequestContext, StoredBodySupplier, Void> requestStartCb =
        cbp.getCallback(Events.REQUEST_BODY_START);
    BiFunction<RequestContext, StoredBodySupplier, Flow<Void>> requestEndedCb =
        cbp.getCallback(Events.REQUEST_BODY_DONE);
    if (requestStartCb == null || requestEndedCb == null) {
      return null;
    }

    int lengthHint = parseLengthHeader(contentLengthHeader);

    return new StoredByteBody(requestContext, requestStartCb, requestEndedCb, charset, lengthHint);
  }

  public static StoredCharBody maybeCreateForChar(String contentLengthHeader) {
    AgentSpan agentSpan = AgentTracer.activeSpan();
    if (agentSpan == null) {
      return null;
    }
    return maybeCreateForChar(agentSpan, contentLengthHeader);
  }

  @SuppressWarnings("Duplicates")
  public static StoredCharBody maybeCreateForChar(AgentSpan agentSpan, String contentLengthHeader) {
    RequestContext requestContext = agentSpan.getRequestContext();
    if (requestContext == null) {
      return null;
    }

    CallbackProvider cbp = AgentTracer.get().instrumentationGateway();
    BiFunction<RequestContext, StoredBodySupplier, Void> requestStartCb =
        cbp.getCallback(Events.REQUEST_BODY_START);
    BiFunction<RequestContext, StoredBodySupplier, Flow<Void>> requestEndedCb =
        cbp.getCallback(Events.REQUEST_BODY_DONE);
    if (requestStartCb == null || requestEndedCb == null) {
      return null;
    }

    int lengthHint = parseLengthHeader(contentLengthHeader);

    return new StoredCharBody(requestContext, requestStartCb, requestEndedCb, lengthHint);
  }

  private static int parseLengthHeader(String contentLengthHeader) {
    if (contentLengthHeader != null) {
      try {
        return Integer.parseInt(contentLengthHeader);
      } catch (NumberFormatException nfe) {
        // purposefully left blank
      }
    }
    return 0;
  }
}
