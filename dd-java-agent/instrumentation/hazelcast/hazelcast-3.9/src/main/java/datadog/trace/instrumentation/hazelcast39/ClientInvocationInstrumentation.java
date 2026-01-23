package datadog.trace.instrumentation.hazelcast39;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.hazelcast39.ClientInvocationDecorator.DECORATE;
import static datadog.trace.instrumentation.hazelcast39.HazelcastConstants.SPAN_NAME;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.hazelcast.client.impl.protocol.ClientMessage;
import com.hazelcast.client.proxy.ClientMapProxy;
import com.hazelcast.client.spi.impl.ClientInvocation;
import com.hazelcast.client.spi.impl.ClientInvocationFuture;
import com.hazelcast.client.spi.impl.NonSmartClientInvocationService;
import com.hazelcast.core.HazelcastInstance;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;

public final class ClientInvocationInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  @Override
  public String instrumentedType() {
    return "com.hazelcast.client.spi.impl.ClientInvocation";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("invokeOnSelection")), getClass().getName() + "$InvocationAdvice");
    transformer.applyAdvice(
        isConstructor()
            .and(
                takesArgument(
                    0,
                    namedOneOf(
                        "com.hazelcast.client.impl.HazelcastClientInstanceImpl",
                        "com.hazelcast.client.impl.clientside.HazelcastClientInstanceImpl"))),
        getClass().getName() + "$ConstructAdvice");
  }

  /** Advice for instrumenting distributed object client proxy classes. */
  public static class InvocationAdvice {

    /** Method entry instrumentation. */
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope methodEnter(
        @Advice.This final ClientInvocation that,
        @Advice.FieldValue("objectName") final String objectName,
        @Advice.FieldValue("clientMessage") final ClientMessage clientMessage) {

      final String operationName =
          InstrumentationContext.get(ClientMessage.class, String.class).get(clientMessage);

      // Ensure that we only create a span for the top-level Hazelcast method; except in the
      // case of async operations where we want visibility into how long the task was delayed from
      // starting. Our call depth checker does not span threads, so the async case is handled
      // automatically for us.
      final int callDepth = CallDepthThreadLocalMap.incrementCallDepth(ClientInvocation.class);
      if (callDepth > 0) {
        return null;
      }

      final AgentSpan span = startSpan(SPAN_NAME);
      DECORATE.onHazelcastInstance(
          span, InstrumentationContext.get(ClientInvocation.class, String.class).get(that));
      DECORATE.afterStart(span);
      DECORATE.onServiceExecution(
          span, operationName, objectName, clientMessage.getCorrelationId());

      return activateSpan(span);
    }

    /** Method exit instrumentation. */
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void methodExit(
        @Advice.Enter final AgentScope scope,
        @Advice.Thrown final Throwable throwable,
        @Advice.FieldValue("clientInvocationFuture") final ClientInvocationFuture future) {
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
          span.finish();
        } else {
          future.andThen(new SpanFinishingExecutionCallback(span));
        }
      } finally {
        scope.close();
        CallDepthThreadLocalMap.reset(ClientInvocation.class); // reset call depth count
      }
    }

    public static void muzzleCheck(
        // Moved in 4.0
        ClientMapProxy proxy,

        // Renamed in 3.9
        NonSmartClientInvocationService invocationService) {
      proxy.getServiceName();
      invocationService.start();
    }
  }

  public static class ConstructAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void constructorExit(
        @Advice.This ClientInvocation that,
        @Advice.Argument(0) final HazelcastInstance hazelcastInstance) {

      if (hazelcastInstance.getLifecycleService() != null
          && hazelcastInstance.getLifecycleService().isRunning()) {
        InstrumentationContext.get(ClientInvocation.class, String.class)
            .put(that, hazelcastInstance.getName());
      }
    }

    public static void muzzleCheck(
        // Moved in 4.0
        ClientMapProxy proxy,

        // Renamed in 3.9
        NonSmartClientInvocationService invocationService) {
      proxy.getServiceName();
      invocationService.start();
    }
  }
}
