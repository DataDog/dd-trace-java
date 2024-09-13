package datadog.trace.instrumentation.java.lang;

import static datadog.trace.api.gateway.Events.EVENTS;

import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.api.Config;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.function.BiFunction;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ShellCmdRaspHelper {

  public static ShellCmdRaspHelper INSTANCE = new ShellCmdRaspHelper();

  private static final Logger LOGGER = LoggerFactory.getLogger(ShellCmdRaspHelper.class);

  private ShellCmdRaspHelper() {
    // prevent instantiation
  }

  public void beforeShellCmd(@Nonnull final String cmd) {
    if (!Config.get().isAppSecRaspEnabled()) {
      return;
    }
    try {
      final BiFunction<RequestContext, String, Flow<Void>> shellCmdCallback =
          AgentTracer.get()
              .getCallbackProvider(RequestContextSlot.APPSEC)
              .getCallback(EVENTS.shellCmd());

      if (shellCmdCallback == null) {
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

      Flow<Void> flow = shellCmdCallback.apply(ctx, cmd);
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
        throw new BlockingException("Blocked request (for SHI attempt)");
      }
    } catch (final BlockingException e) {
      // re-throw blocking exceptions
      throw e;
    } catch (final Throwable e) {
      // suppress anything else
      LOGGER.debug("Exception during SHI rasp callback", e);
    }
  }
}
