package datadog.trace.instrumentation.akkahttp;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import akka.http.scaladsl.model.HttpRequest;
import akka.http.scaladsl.model.HttpResponse;
import akka.stream.Materializer;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.asm.Advice;
import scala.Function1;
import scala.concurrent.Future;

/**
 * Http2 support in akka-http is handled by a separate {@code Http2} extension that only supports
 * {@code bindAndHandleAsync}.
 */
public final class AkkaHttp2ServerInstrumentation
    implements Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {"akka.http.scaladsl.Http2Ext", "akka.http.impl.engine.http2.Http2Ext"};
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        takesArguments(8)
            .and(named("bindAndHandleAsync"))
            .and(takesArgument(0, named("scala.Function1")))
            .and(takesArgument(7, named("akka.stream.Materializer"))),
        getClass().getName() + "$Http2BindAndHandleAsync8ArgAdvice");
    transformer.applyAdvice(
        takesArguments(7)
            .and(named("bindAndHandleAsync"))
            .and(takesArgument(0, named("scala.Function1")))
            .and(takesArgument(6, named("akka.stream.Materializer"))),
        getClass().getName() + "$Http2BindAndHandleAsync7ArgAdvice");
    transformer.applyAdvice(
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
      handler = new DatadogAsyncHandlerWrapper(handler, materializer);
    }
  }

  public static class Http2BindAndHandleAsync7ArgAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter(
        @Advice.Argument(value = 0, readOnly = false)
            Function1<HttpRequest, Future<HttpResponse>> handler,
        @Advice.Argument(value = 6) final Materializer materializer) {
      handler = new DatadogAsyncHandlerWrapper(handler, materializer);
    }
  }

  public static class Http2BindAndHandleAsync6ArgAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter(
        @Advice.Argument(value = 0, readOnly = false)
            Function1<HttpRequest, Future<HttpResponse>> handler,
        @Advice.Argument(value = 5) final Materializer materializer) {
      handler = new DatadogAsyncHandlerWrapper(handler, materializer);
    }
  }
}
