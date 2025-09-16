package datadog.trace.instrumentation.play26;

import static datadog.trace.api.gateway.Events.EVENTS;
import static datadog.trace.bootstrap.instrumentation.decorator.http.HttpResourceDecorator.HTTP_RESOURCE_DECORATOR;

import datadog.context.Context;
import datadog.trace.api.Config;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.ResourceNamePriorities;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.URIUtils;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.concurrent.CompletionException;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.api.mvc.Headers;
import play.api.mvc.Request;
import play.api.mvc.Result;
import play.api.mvc.request.RemoteConnection;
import play.api.routing.HandlerDef;
import play.libs.typedmap.TypedKey;
import play.routing.Router;
import scala.Option;

public class PlayHttpServerDecorator
    extends HttpServerDecorator<Request<?>, Request<?>, Result, Headers> {
  private static final Logger LOG = LoggerFactory.getLogger(PlayHttpServerDecorator.class);
  public static final boolean REPORT_HTTP_STATUS = Config.get().getPlayReportHttpStatus();
  public static final CharSequence PLAY_REQUEST = UTF8BytesString.create("play.request");
  public static final CharSequence PLAY_ACTION = UTF8BytesString.create("play-action");
  public static final PlayHttpServerDecorator DECORATE = new PlayHttpServerDecorator();

  private static final MethodHandle TYPED_KEY_GET_UNDERLYING;

  private static final DDCache<String, CharSequence> PATH_CACHE = DDCaches.newFixedSizeCache(32);

  static {
    MethodHandles.Lookup lookup = MethodHandles.publicLookup();
    MethodHandle typedKeyGetUnderlyingCheck = null;
    try {
      // This method was added in Play 2.6.8
      typedKeyGetUnderlyingCheck =
          lookup.findVirtual(
              TypedKey.class,
              "asScala",
              MethodType.methodType(play.api.libs.typedmap.TypedKey.class));
    } catch (final NoSuchMethodException | IllegalAccessException ignored) {
    }
    // Fallback
    if (typedKeyGetUnderlyingCheck == null) {
      try {
        typedKeyGetUnderlyingCheck =
            lookup.findGetter(TypedKey.class, "underlying", play.api.libs.typedmap.TypedKey.class);
      } catch (final NoSuchFieldException | IllegalAccessException ignored) {
      }
    }
    TYPED_KEY_GET_UNDERLYING = typedKeyGetUnderlyingCheck;
  }

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
  protected AgentPropagation.ContextVisitor<Result> responseGetter() {
    return PlayHeaders.Result.GETTER;
  }

  @Override
  public CharSequence spanName() {
    return PLAY_REQUEST;
  }

  @Override
  protected String method(final Request<?> httpRequest) {
    return httpRequest.method();
  }

  @Override
  protected URIDataAdapter url(final Request<?> request) {
    return new RequestURIDataAdapter(request);
  }

  @Override
  protected String peerHostIP(final Request<?> request) {
    RemoteConnection connection = request.connection();
    if (connection instanceof RemoteConnectionWithRawAddress) {
      return ((RemoteConnectionWithRawAddress) connection).rawRemoteAddressString();
    } else {
      return request.remoteAddress();
    }
  }

  @Override
  protected int peerPort(final Request<?> request) {
    return 0;
  }

  @Override
  protected int status(final Result httpResponse) {
    return httpResponse.header().status();
  }

  @Override
  public AgentSpan onRequest(
      final AgentSpan span,
      final Request<?> connection,
      final Request<?> request,
      final Context parentContext) {
    super.onRequest(span, connection, request, parentContext);
    if (request != null) {
      // more about routes here:
      // https://github.com/playframework/playframework/blob/master/documentation/manual/releases/release26/migration26/Migration26.md
      Option<HandlerDef> defOption = Option.empty();
      if (TYPED_KEY_GET_UNDERLYING != null) { // Should always be non-null but just to make sure
        try {
          defOption =
              request
                  .attrs()
                  .get(
                      (play.api.libs.typedmap.TypedKey<HandlerDef>)
                          TYPED_KEY_GET_UNDERLYING.invokeExact(Router.Attrs.HANDLER_DEF));
        } catch (final Throwable ignored) {
        }
      }
      if (!defOption.isEmpty()) {
        CharSequence path =
            PATH_CACHE.computeIfAbsent(
                defOption.get().path(), p -> addMissingSlash(p, request.path()));
        HTTP_RESOURCE_DECORATOR.withRoute(span, request.method(), path, true);
        dispatchRoute(span, path);
      }
    }
    return span;
  }

  /**
   * Play does not set the http.route in the local root span so we need to store it in the context
   * for API security
   */
  private void dispatchRoute(final AgentSpan span, final CharSequence route) {
    try {
      final RequestContext ctx = span.getRequestContext();
      if (ctx == null) {
        return;
      }
      // Send event to IAST provider
      final CallbackProvider cbpIast = tracer().getCallbackProvider(RequestContextSlot.IAST);
      if (cbpIast != null) {
        final BiConsumer<RequestContext, String> cb = cbpIast.getCallback(EVENTS.httpRoute());
        if (cb != null) {
          cb.accept(ctx, URIUtils.decode(route.toString()));
        }
      }
      // Send event to AppSec provider
      final CallbackProvider cbp = tracer().getCallbackProvider(RequestContextSlot.APPSEC);
      if (cbp != null) {
        final BiConsumer<RequestContext, String> cb = cbp.getCallback(EVENTS.httpRoute());
        if (cb != null) {
          cb.accept(ctx, URIUtils.decode(route.toString()));
        }
      }
    } catch (final Throwable t) {
      LOG.debug("Failed to dispatch route", t);
    }
  }

  /*
     This is a workaround to add a `/` if it is missing when using split routes.

     https://www.playframework.com/documentation/2.8.x/sbtSubProjects#Splitting-the-route-file
     PlayFramework routes compiler generates play.api.routing.HandlerDef.path without a `/`.

     https://github.com/playframework/playframework/blob/main/dev-mode/routes-compiler/src/main/twirl/play/routes/compiler/inject/forwardsRouter.scala.twirl#L70
  */
  private CharSequence addMissingSlash(String routePath, String rawPath) {
    int i = 0;
    int m = Math.min(routePath.length(), rawPath.length());
    while (i < m && routePath.charAt(i) == rawPath.charAt(i)) {
      i++;
    }
    if (i < routePath.length() && routePath.charAt(i) != '/' && rawPath.charAt(i) == '/') {
      StringBuilder sb = new StringBuilder(routePath.length() + 1);
      sb.append(routePath, 0, i);
      sb.append('/');
      sb.append(routePath, i, routePath.length());
      return sb;
    }
    return routePath;
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

  public void updateOn404Only(final AgentSpan span, final Result result) {
    if (SHOULD_SET_404_RESOURCE_NAME && status(result) == 404) {
      span.setResourceName(NOT_FOUND_RESOURCE_NAME, ResourceNamePriorities.HTTP_404);
    }
  }
}
