package datadog.trace.bootstrap.instrumentation.decorator;

import static datadog.trace.api.cache.RadixTreeCache.UNSET_STATUS;
import static datadog.trace.api.gateway.Events.EVENTS;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.traceConfig;
import static datadog.trace.bootstrap.instrumentation.decorator.http.HttpResourceDecorator.HTTP_RESOURCE_DECORATOR;

import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.api.ProductActivation;
import datadog.trace.api.appsec.HttpClientRequest;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.sink.SsrfModule;
import datadog.trace.api.naming.SpanNaming;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.URIUtils;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.BitSet;
import java.util.Map;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class HttpClientDecorator<REQUEST, RESPONSE> extends UriBasedClientDecorator {

  private static final Logger log = LoggerFactory.getLogger(HttpClientDecorator.class);

  private static final BitSet CLIENT_ERROR_STATUSES = Config.get().getHttpClientErrorStatuses();

  private static final UTF8BytesString DEFAULT_RESOURCE_NAME = UTF8BytesString.create("/");

  private static final boolean CLIENT_TAG_HEADERS = Config.get().isHttpClientTagHeaders();

  private static final boolean APPSEC_RASP_ENABLED = Config.get().isAppSecRaspEnabled();

  protected abstract String method(REQUEST request);

  protected abstract URI url(REQUEST request) throws URISyntaxException;

  protected abstract int status(RESPONSE response);

  protected abstract String getRequestHeader(REQUEST request, String headerName);

  protected abstract String getResponseHeader(RESPONSE response, String headerName);

  @Override
  protected CharSequence spanType() {
    return InternalSpanTypes.HTTP_CLIENT;
  }

  @Override
  protected String service() {
    return null;
  }

  protected boolean shouldSetResourceName() {
    return true;
  }

  public AgentSpan onRequest(final AgentSpan span, final REQUEST request) {
    if (request != null) {
      // AgentTracer.get().getDataStreamsMonitoring().trackTransaction();

      // TODO: krigor
      // getRequestHeader(request, "")

      String method = method(request);
      span.setTag(Tags.HTTP_METHOD, method);

      if (CLIENT_TAG_HEADERS) {
        for (Map.Entry<String, String> headerTag :
            traceConfig(span).getRequestHeaderTags().entrySet()) {
          String headerValue = getRequestHeader(request, headerTag.getKey());
          if (null != headerValue) {
            span.setTag(headerTag.getValue(), headerValue);
          }
        }
      }

      // Copy of HttpServerDecorator url handling
      try {
        final URI url = url(request);
        if (url != null) {
          onURI(span, url);
          span.setTag(
              Tags.HTTP_URL,
              URIUtils.lazyValidURL(url.getScheme(), url.getHost(), url.getPort(), url.getPath()));
          if (Config.get().isHttpClientTagQueryString()) {
            span.setTag(DDTags.HTTP_QUERY, url.getQuery());
            span.setTag(DDTags.HTTP_FRAGMENT, url.getFragment());
          }
          if (shouldSetResourceName()) {
            HTTP_RESOURCE_DECORATOR.withClientPath(span, method, url.getPath());
          }
          // SSRF exploit prevention check
          onHttpClientRequest(span, url.toString());
        } else if (shouldSetResourceName()) {
          span.setResourceName(DEFAULT_RESOURCE_NAME);
        }
      } catch (final BlockingException e) {
        throw e;
      } catch (final Exception e) {
        log.debug("Error tagging url", e);
      } finally {
        ssrfIastCheck(request);
      }
    }
    return span;
  }

  public AgentSpan onResponse(final AgentSpan span, final RESPONSE response) {
    if (response != null) {
      final int status = status(response);
      if (status > UNSET_STATUS) {
        span.setHttpStatusCode(status);
        if (CLIENT_ERROR_STATUSES.get(status)) {
          span.setError(true);
        }
      }

      if (CLIENT_TAG_HEADERS) {
        for (Map.Entry<String, String> headerTag :
            traceConfig(span).getResponseHeaderTags().entrySet()) {
          String headerValue = getResponseHeader(response, headerTag.getKey());
          if (null != headerValue) {
            span.setTag(headerTag.getValue(), headerValue);
          }
        }
      }
    }
    return span;
  }

  public String operationName() {
    return SpanNaming.instance()
        .namingSchema()
        .client()
        .operationForComponent(component().toString());
  }

  public String getSpanTagAsString(AgentSpan span, String tag) {
    Object value = span.getTag(tag);
    return value == null ? null : value.toString();
  }

  public long getRequestContentLength(final REQUEST request) {
    if (request == null) {
      return 0;
    }

    String contentLengthStr = getRequestHeader(request, "Content-Length");
    if (contentLengthStr != null) {
      try {
        return Long.parseLong(contentLengthStr);
      } catch (NumberFormatException ignored) {
      }
    }

    return 0;
  }

  public long getResponseContentLength(final RESPONSE response) {
    if (response == null) {
      return 0;
    }

    String contentLengthStr = getResponseHeader(response, "Content-Length");
    if (contentLengthStr != null) {
      try {
        return Long.parseLong(contentLengthStr);
      } catch (NumberFormatException ignored) {
      }
    }

    return 0;
  }

  protected void onHttpClientRequest(final AgentSpan span, final String url) {
    if (!APPSEC_RASP_ENABLED) {
      return;
    }
    if (url == null) {
      return;
    }
    final BiFunction<RequestContext, HttpClientRequest, Flow<Void>> requestCb =
        AgentTracer.get()
            .getCallbackProvider(RequestContextSlot.APPSEC)
            .getCallback(EVENTS.httpClientRequest());

    if (requestCb == null) {
      return;
    }

    final RequestContext ctx = span.getRequestContext();
    if (ctx == null) {
      return;
    }

    final long requestId = span.getSpanId();
    Flow<Void> flow = requestCb.apply(ctx, new HttpClientRequest(requestId, url));
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
  }

  /* This method must be overriden after making the proper propagations to the client before **/
  protected Object sourceUrl(REQUEST request) {
    return null;
  }

  private void ssrfIastCheck(final REQUEST request) {
    final Object sourceUrl = sourceUrl(request);
    if (sourceUrl == null) {
      return;
    }
    if (InstrumenterConfig.get().getIastActivation() != ProductActivation.FULLY_ENABLED) {
      return;
    }
    final SsrfModule ssrfModule = InstrumentationBridge.SSRF;
    if (ssrfModule != null) {
      ssrfModule.onURLConnection(sourceUrl);
    }
  }
}
