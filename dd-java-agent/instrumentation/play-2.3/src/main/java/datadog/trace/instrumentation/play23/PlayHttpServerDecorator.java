package datadog.trace.instrumentation.play23;

import static datadog.trace.api.gateway.Events.EVENTS;
import static datadog.trace.bootstrap.instrumentation.decorator.http.HttpResourceDecorator.HTTP_RESOURCE_DECORATOR;

import datadog.trace.api.Config;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.concurrent.CompletionException;
import java.util.function.BiConsumer;
import play.api.Routes;
import play.api.mvc.Headers;
import play.api.mvc.Request;
import scala.Option;

public class PlayHttpServerDecorator
    extends HttpServerDecorator<Request, Request, play.api.mvc.Result, Headers> {
  public static final boolean REPORT_HTTP_STATUS = Config.get().getPlayReportHttpStatus();
  public static final CharSequence PLAY_REQUEST = UTF8BytesString.create("play.request");
  public static final CharSequence PLAY_ACTION = UTF8BytesString.create("play-action");
  public static final PlayHttpServerDecorator DECORATE = new PlayHttpServerDecorator();

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"play"};
  }

  @Override
  protected CharSequence component() {
    return PLAY_ACTION;
  }

  @Override
  protected AgentPropagation.ContextVisitor<Headers> getter() {
    return PlayHeaders.Request.GETTER;
  }

  @Override
  protected AgentPropagation.ContextVisitor<play.api.mvc.Result> responseGetter() {
    return PlayHeaders.Result.GETTER;
  }

  @Override
  public CharSequence spanName() {
    return PLAY_REQUEST;
  }

  @Override
  protected String method(final Request httpRequest) {
    return httpRequest.method();
  }

  @Override
  protected URIDataAdapter url(final Request request) {
    return new RequestURIDataAdapter(request);
  }

  @Override
  protected String peerHostIP(final Request request) {
    return request.remoteAddress();
  }

  @Override
  protected int peerPort(final Request request) {
    return 0;
  }

  @Override
  protected int status(final play.api.mvc.Result httpResponse) {
    return httpResponse.header().status();
  }

  @Override
  public AgentSpan onRequest(
      final AgentSpan span,
      final Request connection,
      final Request request,
      AgentSpanContext.Extracted context) {
    super.onRequest(span, connection, request, context);
    if (request != null) {
      // more about routes here:
      // https://github.com/playframework/playframework/blob/master/documentation/manual/releases/release26/migration26/Migration26.md#router-tags-are-now-attributes
      final Option pathOption = request.tags().get(Routes.ROUTE_PATTERN());
      if (!pathOption.isEmpty()) {
        final String path = (String) pathOption.get();
        handleRoute(span, request.method(), path);
      }
    }
    return span;
  }

  private void handleRoute(final AgentSpan span, final String method, final String route) {
    HTTP_RESOURCE_DECORATOR.withRoute(span, method, route);
    // play does not set the http.route in the local root span so we need to store it in the context
    // for API security
    final RequestContext ctx = span.getRequestContext();
    if (ctx != null) {
      final BiConsumer<RequestContext, String> cb =
          AgentTracer.get()
              .getCallbackProvider(RequestContextSlot.APPSEC)
              .getCallback(EVENTS.httpRoute());
      if (cb != null) {
        cb.accept(ctx, route);
      }
    }
  }

  @Override
  public AgentSpan onError(final AgentSpan span, Throwable throwable) {
    if (REPORT_HTTP_STATUS) {
      span.setHttpStatusCode(500);
    }
    if (throwable instanceof CompletionException && throwable.getCause() != null) {
      throwable = throwable.getCause();
    }
    while ((throwable instanceof InvocationTargetException
            || throwable instanceof UndeclaredThrowableException)
        && throwable.getCause() != null) {
      throwable = throwable.getCause();
    }
    return super.onError(span, throwable);
  }
}
