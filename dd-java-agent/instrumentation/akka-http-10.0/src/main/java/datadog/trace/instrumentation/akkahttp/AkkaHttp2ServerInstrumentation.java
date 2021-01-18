package datadog.trace.instrumentation.akkahttp;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import akka.http.scaladsl.model.HttpRequest;
import akka.http.scaladsl.model.HttpResponse;
import akka.stream.Materializer;
import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import scala.Function1;
import scala.concurrent.Future;

/**
 * Http2 support in akka-http is handled by a separate {@code Http2} extension that only supports
 * {@code bindAndHandleAsync}.
 */
@Slf4j
@AutoService(Instrumenter.class)
public final class AkkaHttp2ServerInstrumentation extends Instrumenter.Tracing {
  public AkkaHttp2ServerInstrumentation() {
    super("akka-http2", "akka-http", "akka-http-server");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("akka.http.scaladsl.Http2Ext").or(named("akka.http.impl.engine.http2.Http2Ext"));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".DatadogWrapperHelper",
      packageName + ".DatadogAsyncHandlerWrapper",
      packageName + ".DatadogAsyncHandlerWrapper$1",
      packageName + ".DatadogAsyncHandlerWrapper$2",
      packageName + ".AkkaHttpServerHeaders",
      packageName + ".AkkaHttpServerDecorator",
      packageName + ".UriAdapter",
    };
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    Map<ElementMatcher<MethodDescription>, String> transformers = new HashMap<>(4);
    transformers.put(
        takesArguments(8)
            .and(named("bindAndHandleAsync"))
            .and(takesArgument(0, named("scala.Function1")))
            .and(takesArgument(7, named("akka.stream.Materializer"))),
        getClass().getName() + "$Http2BindAndHandleAsync8ArgAdvice");
    transformers.put(
        takesArguments(7)
            .and(named("bindAndHandleAsync"))
            .and(takesArgument(0, named("scala.Function1")))
            .and(takesArgument(6, named("akka.stream.Materializer"))),
        getClass().getName() + "$Http2BindAndHandleAsync7ArgAdvice");
    transformers.put(
        takesArguments(6)
            .and(named("bindAndHandleAsync"))
            .and(takesArgument(0, named("scala.Function1")))
            .and(takesArgument(5, named("akka.stream.Materializer"))),
        getClass().getName() + "$Http2BindAndHandleAsync6ArgAdvice");
    return Collections.unmodifiableMap(transformers);
  }

  public static class Http2BindAndHandleAsync8ArgAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter(
        @Advice.Argument(value = 0, readOnly = false)
            Function1<HttpRequest, Future<HttpResponse>> handler,
        @Advice.Argument(value = 7) final Materializer materializer) {
      handler = new DatadogAsyncHandlerWrapper(handler, materializer.executionContext());
    }
  }

  public static class Http2BindAndHandleAsync7ArgAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter(
        @Advice.Argument(value = 0, readOnly = false)
            Function1<HttpRequest, Future<HttpResponse>> handler,
        @Advice.Argument(value = 6) final Materializer materializer) {
      handler = new DatadogAsyncHandlerWrapper(handler, materializer.executionContext());
    }
  }

  public static class Http2BindAndHandleAsync6ArgAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter(
        @Advice.Argument(value = 0, readOnly = false)
            Function1<HttpRequest, Future<HttpResponse>> handler,
        @Advice.Argument(value = 5) final Materializer materializer) {
      handler = new DatadogAsyncHandlerWrapper(handler, materializer.executionContext());
    }
  }
}
