package datadog.trace.instrumentation.apachehttpclient5;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.captureActiveSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.apachehttpclient5.ApacheHttpClientDecorator.DECORATE;
import static datadog.trace.instrumentation.apachehttpclient5.ApacheHttpClientDecorator.HTTP_REQUEST;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.nio.AsyncRequestProducer;
import org.apache.hc.core5.http.protocol.BasicHttpContext;
import org.apache.hc.core5.http.protocol.HttpContext;

@AutoService(InstrumenterModule.class)
public class ApacheHttpAsyncClientInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.CanShortcutTypeMatching, Instrumenter.HasMethodAdvice {

  public ApacheHttpAsyncClientInstrumentation() {
    super(
        "httpasyncclient5", "apache-httpasyncclient5", "httpasyncclient", "apache-httpasyncclient");
  }

  @Override
  public boolean onlyMatchKnownTypes() {
    return isShortcutMatchingEnabled(false);
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient",
      "org.apache.hc.client5.http.impl.async.AbstractHttpAsyncClientBase",
      "org.apache.hc.client5.http.impl.async.AbstractMinimalHttpAsyncClientBase",
      "org.apache.hc.client5.http.impl.async.MinimalHttpAsyncClient",
      "org.apache.hc.client5.http.impl.async.MinimalH2AsyncClient",
      "org.apache.hc.client5.http.impl.async.InternalAbstractHttpAsyncClient",
      "org.apache.hc.client5.http.impl.async.InternalHttpAsyncClient",
      "org.apache.hc.client5.http.impl.async.InternalH2AsyncClient"
    };
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.apache.hc.client5.http.async.HttpAsyncClient";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".ApacheHttpClientDecorator",
      packageName + ".HttpHeadersInjectAdapter",
      packageName + ".DelegatingRequestChannel",
      packageName + ".DelegatingRequestProducer",
      packageName + ".TraceContinuedFutureCallback"
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("execute"))
            .and(takesArguments(5))
            .and(takesArgument(0, named("org.apache.hc.core5.http.nio.AsyncRequestProducer")))
            .and(takesArgument(1, named("org.apache.hc.core5.http.nio.AsyncResponseConsumer")))
            .and(takesArgument(2, named("org.apache.hc.core5.http.nio.HandlerFactory")))
            .and(takesArgument(3, named("org.apache.hc.core5.http.protocol.HttpContext")))
            .and(takesArgument(4, named("org.apache.hc.core5.concurrent.FutureCallback"))),
        this.getClass().getName() + "$ClientAdvice");
  }

  public static class ClientAdvice {
    @SuppressFBWarnings("UC_USELESS_OBJECT")
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope methodEnter(
        @Advice.Argument(value = 0, readOnly = false) AsyncRequestProducer requestProducer,
        @Advice.Argument(value = 3, readOnly = false) HttpContext context,
        @Advice.Argument(value = 4, readOnly = false) FutureCallback<?> futureCallback) {

      final AgentScope.Continuation parentContinuation = captureActiveSpan();
      final AgentSpan clientSpan = startSpan(HTTP_REQUEST);
      final AgentScope clientScope = activateSpan(clientSpan);
      DECORATE.afterStart(clientSpan);

      if (context == null) {
        context = new BasicHttpContext();
      }

      requestProducer = new DelegatingRequestProducer(clientSpan, requestProducer);
      futureCallback =
          new TraceContinuedFutureCallback<>(
              parentContinuation, clientSpan, context, futureCallback);

      return clientScope;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void methodExit(
        @Advice.Enter final AgentScope scope,
        @Advice.Return final Object result,
        @Advice.Thrown final Throwable throwable) {
      if (scope == null) {
        return;
      }
      try {
        if (throwable != null) {
          final AgentSpan span = scope.span();
          DECORATE.onError(span, throwable);
          DECORATE.beforeFinish(span);
          span.finish();
        }
      } finally {
        scope.close();
      }
    }
  }
}
