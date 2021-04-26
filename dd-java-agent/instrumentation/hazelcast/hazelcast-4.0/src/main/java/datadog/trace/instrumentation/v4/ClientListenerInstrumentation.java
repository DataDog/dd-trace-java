package datadog.trace.instrumentation.v4;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.hazelcast.HazelcastConstants.HAZELCAST_SDK;
import static datadog.trace.instrumentation.v4.ClientInvocationDecorator.DECORATE;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import com.hazelcast.client.ClientListener;
import com.hazelcast.client.impl.protocol.ClientMessage;
import com.hazelcast.client.impl.proxy.ClientMapProxy;
import com.hazelcast.client.impl.spi.ClientListenerService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.instrumentation.hazelcast.HazelcastConstants;
import java.util.Collections;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class ClientListenerInstrumentation extends Instrumenter.Tracing {

  public ClientListenerInstrumentation() {
    super(HazelcastConstants.INSTRUMENTATION_NAME);
  }

  @Override
  protected boolean defaultEnabled() {
    return HazelcastConstants.DEFAULT_ENABLED;
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return named("com.hazelcast.client.impl.spi.impl.listener.ClientListenerServiceImpl");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".ClientInvocationDecorator",
      "datadog.trace.instrumentation.hazelcast.HazelcastConstants"
    };
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return Collections.singletonMap(
        isMethod()
            .and(named("handleEventMessageOnCallingThread"))
            .and(takesArgument(0, named("com.hazelcast.client.impl.protocol.ClientMessage"))),
        getClass().getName() + "$ListenerAdvice");
  }

  /** Advice for instrumenting distributed object client proxy classes. */
  public static class ListenerAdvice {

    /** Method entry instrumentation. */
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope methodEnter(
        @Advice.This final ClientListenerService that,
        @Advice.Argument(0) final ClientMessage clientMessage) {

      System.err.println("Message Received: " + clientMessage);

      final String operationName =
          clientMessage.getOperationName() != null
              ? clientMessage.getOperationName()
              : "Event.Handle";
      long correlationId = clientMessage.getCorrelationId();

      // Ensure that we only create a span for the top-level Hazelcast method; except in the
      // case of async operations where we want visibility into how long the task was delayed from
      // starting. Our call depth checker does not span threads, so the async case is handled
      // automatically for us.
      final int callDepth = CallDepthThreadLocalMap.incrementCallDepth(ClientListener.class);
      if (callDepth > 0) {
        return null;
      }

      final AgentSpan span = startSpan(HAZELCAST_SDK);
      DECORATE.afterStart(span);
      DECORATE.onServiceExecution(span, operationName, null, correlationId);

      return activateSpan(span);
    }

    /** Method exit instrumentation. */
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void methodExit(
        @Advice.Enter final AgentScope scope, @Advice.Thrown final Throwable throwable) {
      if (scope == null) {
        return;
      }

      // If we have a scope (i.e. we were the top-level Hazelcast SDK invocation),
      final AgentSpan span = scope.span();
      try {
        if (throwable != null) {
          // There was an synchronous error,
          // which means we shouldn't wait for a callback to close the span.
          DECORATE.onError(span, throwable);
          DECORATE.beforeFinish(span);
        } else {
          DECORATE.beforeFinish(span);
          DECORATE.onResult(span, null);
        }
      } finally {
        scope.close();
        span.finish();
        CallDepthThreadLocalMap.reset(ClientListener.class); // reset call depth count
      }
    }

    public static void muzzleCheck(
        // Moved in 4.0
        ClientMapProxy proxy) {
      proxy.getServiceName();
    }
  }
}
