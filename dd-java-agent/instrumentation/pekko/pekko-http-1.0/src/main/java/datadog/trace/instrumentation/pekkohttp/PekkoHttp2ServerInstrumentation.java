package datadog.trace.instrumentation.pekkohttp;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.Advice.OnMethodExit;
import org.apache.pekko.http.scaladsl.HttpExt;
import org.apache.pekko.http.scaladsl.model.HttpRequest;
import org.apache.pekko.http.scaladsl.model.HttpResponse;
import org.apache.pekko.stream.Materializer;
import scala.Function1;
import scala.concurrent.Future;

/**
 * Http2 support in pekko-http is handled by a separate {@code Http2} extension that only supports
 * {@code bindAndHandleAsync}.
 */
@AutoService(InstrumenterModule.class)
public final class PekkoHttp2ServerInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {
  public PekkoHttp2ServerInstrumentation() {
    super("pekko-http2", "pekko-http", "pekko-http-server");
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      // pekko 1.1.0 seems not going through Htt2Ext anymore
      "org.apache.pekko.http.scaladsl.HttpExt",
      "org.apache.pekko.http.scaladsl.Http2Ext",
      "org.apache.pekko.http.impl.engine.http2.Http2Ext"
    };
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".DatadogWrapperHelper",
      packageName + ".DatadogAsyncHandlerWrapper",
      packageName + ".DatadogAsyncHandlerWrapper$1",
      packageName + ".DatadogAsyncHandlerWrapper$2",
      packageName + ".PekkoHttpServerHeaders",
      packageName + ".PekkoHttpServerDecorator",
      packageName + ".UriAdapter",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        takesArguments(8)
            .and(named("bindAndHandleAsync"))
            .and(takesArgument(0, named("scala.Function1")))
            .and(takesArgument(7, named("org.apache.pekko.stream.Materializer"))),
        getClass().getName() + "$Http2BindAndHandleAsync8ArgAdvice");
    transformer.applyAdvice(
        takesArguments(7)
            .and(named("bindAndHandleAsync"))
            .and(takesArgument(0, named("scala.Function1")))
            .and(takesArgument(6, named("org.apache.pekko.stream.Materializer"))),
        getClass().getName() + "$Http2BindAndHandleAsync7ArgAdvice");
    transformer.applyAdvice(
        takesArguments(6)
            .and(named("bindAndHandleAsync"))
            .and(takesArgument(0, named("scala.Function1")))
            .and(takesArgument(5, named("org.apache.pekko.stream.Materializer"))),
        getClass().getName() + "$Http2BindAndHandleAsync6ArgAdvice");
  }

  public static class Http2BindAndHandleAsync8ArgAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter(
        @Advice.Argument(value = 0, readOnly = false)
            Function1<HttpRequest, Future<HttpResponse>> handler,
        @Advice.Argument(value = 7) final Materializer materializer) {
      if (CallDepthThreadLocalMap.incrementCallDepth(HttpExt.class) == 0) {
        handler = new DatadogAsyncHandlerWrapper(handler, materializer.executionContext());
      }
    }

    @OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void exit() {
      CallDepthThreadLocalMap.decrementCallDepth(HttpExt.class);
    }
  }

  public static class Http2BindAndHandleAsync7ArgAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter(
        @Advice.Argument(value = 0, readOnly = false)
            Function1<HttpRequest, Future<HttpResponse>> handler,
        @Advice.Argument(value = 6) final Materializer materializer) {
      if (CallDepthThreadLocalMap.incrementCallDepth(HttpExt.class) == 0) {
        handler = new DatadogAsyncHandlerWrapper(handler, materializer.executionContext());
      }
    }

    @OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void exit() {
      CallDepthThreadLocalMap.decrementCallDepth(HttpExt.class);
    }
  }

  public static class Http2BindAndHandleAsync6ArgAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter(
        @Advice.Argument(value = 0, readOnly = false)
            Function1<HttpRequest, Future<HttpResponse>> handler,
        @Advice.Argument(value = 5) final Materializer materializer) {
      if (CallDepthThreadLocalMap.incrementCallDepth(HttpExt.class) == 0) {
        handler = new DatadogAsyncHandlerWrapper(handler, materializer.executionContext());
      }
    }

    @OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void exit() {
      CallDepthThreadLocalMap.decrementCallDepth(HttpExt.class);
    }
  }
}
