package datadog.trace.instrumentation.java.net;

import static datadog.trace.api.gateway.Events.EVENTS;

import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.Config;
import datadog.trace.api.appsec.HttpClientRequest;
import datadog.trace.api.appsec.RaspCallSites;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.iast.IastCallSites;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Sink;
import datadog.trace.api.iast.VulnerabilityTypes;
import datadog.trace.api.iast.sink.SsrfModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.net.URL;
import java.util.function.BiFunction;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@CallSite(spi = {IastCallSites.class, RaspCallSites.class})
public class URLSinkCallSite {

  private static final Logger LOGGER = LoggerFactory.getLogger(URLSinkCallSite.class);

  @Sink(VulnerabilityTypes.SSRF)
  @CallSite.Before("java.net.URLConnection java.net.URL.openConnection()")
  @CallSite.Before("java.net.URLConnection java.net.URL.openConnection(java.net.Proxy)")
  @CallSite.Before("java.io.InputStream java.net.URL.openStream()")
  @CallSite.Before("java.lang.Object java.net.URL.getContent()")
  @CallSite.Before("java.lang.Object java.net.URL.getContent(java.lang.Class[])")
  public static void beforeOpenConnection(@CallSite.This final URL url) {
    if (url == null) {
      return;
    }
    iastCallback(url);
    raspCallback(url);
  }

  private static void iastCallback(@Nonnull final URL url) {
    final SsrfModule module = InstrumentationBridge.SSRF;
    if (module != null) {
      try {
        module.onURLConnection(url);
      } catch (final Throwable e) {
        module.onUnexpectedException("After open connection threw", e);
      }
    }
  }

  private static void raspCallback(@Nonnull final URL url) {
    if (!Config.get().isAppSecRaspEnabled()) {
      return;
    }

    try {
      final BiFunction<RequestContext, HttpClientRequest, Flow<Void>> httpClientRequestCb =
          AgentTracer.get()
              .getCallbackProvider(RequestContextSlot.APPSEC)
              .getCallback(EVENTS.httpClientRequest());
      if (httpClientRequestCb == null) {
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

      Flow<Void> flow =
          httpClientRequestCb.apply(ctx, new HttpClientRequest(span.getSpanId(), url.toString()));
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
      throw e;
    } catch (final Throwable e) {
      LOGGER.debug("Exception during SSRF rasp callback", e);
    }
  }
}
