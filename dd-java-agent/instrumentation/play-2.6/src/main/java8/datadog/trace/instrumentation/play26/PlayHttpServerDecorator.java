package datadog.trace.instrumentation.play26;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.concurrent.CompletionException;
import lombok.extern.slf4j.Slf4j;
import play.api.mvc.Request;
import play.api.mvc.Result;
import play.api.routing.HandlerDef;
import play.libs.typedmap.TypedKey;
import play.routing.Router;
import scala.Option;

@Slf4j
public class PlayHttpServerDecorator extends HttpServerDecorator<Request, Request, Result> {
  public static final CharSequence PLAY_REQUEST = UTF8BytesString.createConstant("play.request");
  public static final CharSequence PLAY_ACTION = UTF8BytesString.createConstant("play-action");
  public static final PlayHttpServerDecorator DECORATE = new PlayHttpServerDecorator();

  private static final Integer ERROR_CODE = 500;
  private static final MethodHandle TYPED_KEY_GET_UNDERLYING;

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
  protected int status(final Result httpResponse) {
    return httpResponse.header().status();
  }

  @Override
  public AgentSpan onRequest(final AgentSpan span, final Request request) {
    super.onRequest(span, request);
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
        final String path = defOption.get().path();
        span.setResourceName(request.method() + " " + path);
      }
    }
    return span;
  }

  @Override
  public AgentSpan onError(final AgentSpan span, Throwable throwable) {
    span.setTag(Tags.HTTP_STATUS, ERROR_CODE);
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
