package datadog.trace.instrumentation.appsec.rasp.modules;

import static datadog.trace.api.gateway.Events.EVENTS;

import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.api.Config;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.instrumentation.appsec.utils.InstrumentationLogger;
import java.util.function.BiFunction;

public class NetworkConnectionModule {

  public static final NetworkConnectionModule INSTANCE = new NetworkConnectionModule();

  private NetworkConnectionModule() {
    // prevent instantiation
  }

  public void onNetworkConnection(final String networkConnection) {
    if (!Config.get().isAppSecRaspEnabled()) {
      return;
    }
    if (networkConnection == null) {
      return;
    }
    try {
      final BiFunction<RequestContext, String, Flow<Void>> networkConnectionCallback =
          AgentTracer.get()
              .getCallbackProvider(RequestContextSlot.APPSEC)
              .getCallback(EVENTS.networkConnection());

      if (networkConnectionCallback == null) {
        return;
      }

      final AgentSpan span = AgentTracer.get().activeSpan();
      if (span == null) {
        return;
      }

      final RequestContext ctx = span.getRequestContext();
      if (ctx == null) {
        return;
      }

      Flow<Void> flow = networkConnectionCallback.apply(ctx, networkConnection);
      Flow.Action action = flow.getAction();
      if (action instanceof Flow.Action.RequestBlockingAction) {
        BlockResponseFunction brf = ctx.getBlockResponseFunction();
        if (brf != null) {
          Flow.Action.RequestBlockingAction rba = (Flow.Action.RequestBlockingAction) action;
          brf.tryCommitBlockingResponse(
              ctx.getTraceSegment(),
              rba.getStatusCode(),
              rba.getBlockingContentType(),
              rba.getExtraHeaders());
        }
        throw new BlockingException("Blocked request (for SSRF attempt)");
      }
    } catch (final BlockingException e) {
      // re-throw blocking exceptions
      throw e;
    } catch (final Throwable e) {
      // suppress anything else
      InstrumentationLogger.debug(
          "Exception during SSRF rasp advice", NetworkConnectionModule.class, e);
    }
  }
}
