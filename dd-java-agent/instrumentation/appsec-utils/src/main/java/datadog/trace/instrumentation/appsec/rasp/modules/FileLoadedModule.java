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

public class FileLoadedModule {

  public static final FileLoadedModule INSTANCE = new FileLoadedModule();

  private FileLoadedModule() {
    // prevent instantiation
  }

  public void onFileLoaded(final String path) {
    if (!Config.get().isAppSecRaspEnabled()) {
      return;
    }
    if (path == null) {
      return;
    }
    try {
      final BiFunction<RequestContext, String, Flow<Void>> fileLoadedCallback =
          AgentTracer.get()
              .getCallbackProvider(RequestContextSlot.APPSEC)
              .getCallback(EVENTS.fileLoaded());

      if (fileLoadedCallback == null) {
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

      Flow<Void> flow = fileLoadedCallback.apply(ctx, path);
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
        throw new BlockingException("Blocked request (for FLI attempt)");
      }
    } catch (final BlockingException e) {
      // re-throw blocking exceptions
      throw e;
    } catch (final Throwable e) {
      // suppress anything else
      InstrumentationLogger.debug("Exception during FLI rasp advice", FileLoadedModule.class, e);
    }
  }
}
