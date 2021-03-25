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
import play.api.mvc.Request;
import play.api.mvc.Result;
import play.libs.typedmap.TypedKey;

public class PlayHttpServerDecorator extends HttpServerDecorator<Request, Request, Result> {
  public static final CharSequence PLAY_REQUEST = UTF8BytesString.create("play.request");
  public static final CharSequence PLAY_ACTION = UTF8BytesString.create("play-action");
  public static final PlayHttpServerDecorator DECORATE = new PlayHttpServerDecorator();

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
  public AgentSpan onError(final AgentSpan span, Throwable throwable) {
    span.setTag(Tags.HTTP_STATUS, _500);
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
