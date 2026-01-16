package datadog.trace.instrumentation.apachehttpasyncclient;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.captureActiveSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.apachehttpasyncclient.ApacheHttpAsyncClientDecorator.DECORATE;
import static datadog.trace.instrumentation.apachehttpasyncclient.ApacheHttpAsyncClientDecorator.HTTP_REQUEST;
import static java.util.Arrays.asList;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.http.concurrent.FutureCallback;
import org.apache.http.nio.protocol.HttpAsyncRequestProducer;
import org.apache.http.protocol.HttpContext;

public class ApacheHttpAsyncClientInstrumentation
    implements Instrumenter.CanShortcutTypeMatching, Instrumenter.HasMethodAdvice {

  public ApacheHttpAsyncClientInstrumentation() {
    super();
  }

  @Override
  public boolean onlyMatchKnownTypes() {
    return InstrumenterConfig.get()
        .isIntegrationShortcutMatchingEnabled(
            asList("httpasyncclient", "apache-httpasyncclient"), false);
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "org.apache.http.impl.nio.client.AbstractHttpAsyncClient",
      "org.apache.http.impl.nio.client.CloseableHttpAsyncClient",
      "org.apache.http.impl.nio.client.CloseableHttpAsyncClientBase",
      "org.apache.http.impl.nio.client.CloseableHttpPipeliningClient",
      "org.apache.http.impl.nio.client.DefaultHttpAsyncClient",
      "org.apache.http.impl.nio.client.InternalHttpAsyncClient",
      "org.apache.http.impl.nio.client.MinimalHttpAsyncClient"
    };
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.apache.http.nio.client.HttpAsyncClient";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("execute"))
            .and(takesArguments(4))
            .and(takesArgument(0, named("org.apache.http.nio.protocol.HttpAsyncRequestProducer")))
            .and(takesArgument(1, named("org.apache.http.nio.protocol.HttpAsyncResponseConsumer")))
            .and(takesArgument(2, named("org.apache.http.protocol.HttpContext")))
            .and(takesArgument(3, named("org.apache.http.concurrent.FutureCallback"))),
        ApacheHttpAsyncClientInstrumentation.class.getName() + "$ClientAdvice");
  }

  public static class ClientAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentSpan methodEnter(
        @Advice.Argument(value = 0, readOnly = false) HttpAsyncRequestProducer requestProducer,
        @Advice.Argument(2) HttpContext context,
        @Advice.Argument(value = 3, readOnly = false) FutureCallback<?> futureCallback) {

      final AgentScope.Continuation parentContinuation = captureActiveSpan();
      final AgentSpan clientSpan = startSpan(HTTP_REQUEST);
      DECORATE.afterStart(clientSpan);

      requestProducer = new DelegatingRequestProducer(clientSpan, requestProducer);
      futureCallback =
          new TraceContinuedFutureCallback<>(
              parentContinuation, clientSpan, context, futureCallback);

      return clientSpan;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void methodExit(
        @Advice.Enter final AgentSpan span,
        @Advice.Return final Object result,
        @Advice.Thrown final Throwable throwable) {
      if (throwable != null) {
        DECORATE.onError(span, throwable);
        DECORATE.beforeFinish(span);
        span.finish();
      }
    }
  }
}
