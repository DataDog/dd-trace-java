package datadog.trace.instrumentation.akkahttp;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import akka.http.scaladsl.model.HttpRequest;
import akka.http.scaladsl.model.HttpResponse;
import akka.stream.Materializer;
import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.asm.Advice;
import scala.Function1;
import scala.concurrent.Future;

/**
 * Http2 support in akka-http is handled by a separate {@code Http2} extension that only supports
 * {@code bindAndHandleAsync}.
 */
@AutoService(Instrumenter.class)
public final class AkkaHttp2ServerInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForKnownTypes {
  public AkkaHttp2ServerInstrumentation() {
    super("akka-http2", "akka-http", "akka-http-server");
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {"akka.http.scaladsl.Http2Ext", "akka.http.impl.engine.http2.Http2Ext"};
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
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        takesArguments(8)
            .and(named("bindAndHandleAsync"))
            .and(takesArgument(0, named("scala.Function1")))
            .and(takesArgument(7, named("akka.stream.Materializer"))),
        getClass().getName() + "$Http2BindAndHandleAsync8ArgAdvice");
    transformation.applyAdvice(
        takesArguments(7)
            .and(named("bindAndHandleAsync"))
            .and(takesArgument(0, named("scala.Function1")))
            .and(takesArgument(6, named("akka.stream.Materializer"))),
        getClass().getName() + "$Http2BindAndHandleAsync7ArgAdvice");
    transformation.applyAdvice(
        takesArguments(6)
            .and(named("bindAndHandleAsync"))
            .and(takesArgument(0, named("scala.Function1")))
            .and(takesArgument(5, named("akka.stream.Materializer"))),
        getClass().getName() + "$Http2BindAndHandleAsync6ArgAdvice");
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
