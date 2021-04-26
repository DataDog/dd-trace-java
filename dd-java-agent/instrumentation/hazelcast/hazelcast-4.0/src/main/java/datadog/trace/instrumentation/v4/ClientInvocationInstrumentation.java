package datadog.trace.instrumentation.v4;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.hazelcast.HazelcastConstants.HAZELCAST_INSTANCE;
import static datadog.trace.instrumentation.hazelcast.HazelcastConstants.HAZELCAST_SDK;
import static datadog.trace.instrumentation.v4.HazelcastDecorator.DECORATE;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import com.hazelcast.client.impl.clientside.HazelcastClientInstanceImpl;
import com.hazelcast.client.impl.protocol.ClientMessage;
import com.hazelcast.client.impl.proxy.ClientMapProxy;
import com.hazelcast.client.impl.spi.impl.ClientInvocation;
import com.hazelcast.client.impl.spi.impl.ClientInvocationFuture;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.instrumentation.hazelcast.HazelcastConstants;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class ClientInvocationInstrumentation extends Instrumenter.Tracing {

  public ClientInvocationInstrumentation() {
    super(HazelcastConstants.INSTRUMENTATION_NAME);
  }

  @Override
  protected boolean defaultEnabled() {
    return HazelcastConstants.DEFAULT_ENABLED;
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".HazelcastDecorator",
      packageName + ".SpanFinishingExecutionCallback",
      "datadog.trace.instrumentation.hazelcast.HazelcastConstants"
    };
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return named("com.hazelcast.client.impl.spi.impl.ClientInvocation");
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap(
        "com.hazelcast.client.impl.spi.impl.ClientInvocation", String.class.getName());
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    final Map<ElementMatcher<MethodDescription>, String> transformers = new HashMap<>(4);

    transformers.put(
        isMethod().and(named("invokeOnSelection")), getClass().getName() + "$InvocationAdvice");
    transformers.put(
        isConstructor()
            .and(
                takesArgument(
                    0, named("com.hazelcast.client.impl.clientside.HazelcastClientInstanceImpl"))),
        getClass().getName() + "$ConstructAdvice");

    return transformers;
  }

  /** Advice for instrumenting distributed object client proxy classes. */
  public static class InvocationAdvice {

    /** Method entry instrumentation. */
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope methodEnter(
        @Advice.This final ClientInvocation that,
        @Advice.FieldValue("objectName") final Object objectName,
        @Advice.FieldValue("clientMessage") final ClientMessage clientMessage) {

      final String operationName = clientMessage.getOperationName();
      long correlationId = clientMessage.getCorrelationId();

      // Ensure that we only create a span for the top-level Hazelcast method; except in the
      // case of async operations where we want visibility into how long the task was delayed from
      // starting. Our call depth checker does not span threads, so the async case is handled
      // automatically for us.
      final int callDepth = CallDepthThreadLocalMap.incrementCallDepth(ClientInvocation.class);
      if (callDepth > 0) {
        return null;
      }

      final AgentSpan span = startSpan(HAZELCAST_SDK);

      span.setTag(
          HAZELCAST_INSTANCE,
          InstrumentationContext.get(ClientInvocation.class, String.class).get(that));
      DECORATE.afterStart(span);
      DECORATE.onServiceExecution(span, operationName, objectName, correlationId);

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
          future.whenComplete(new SpanFinishingExecutionCallback(span));
        }
      } finally {
        scope.close();
        CallDepthThreadLocalMap.reset(ClientInvocation.class); // reset call depth count
      }
    }

    public static void muzzleCheck(
        // Moved in 4.0
        ClientMapProxy proxy) {
      proxy.getServiceName();
    }
  }

  public static class ConstructAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void constructorExit(
        @Advice.This ClientInvocation that,
        @Advice.Argument(0) final HazelcastClientInstanceImpl hazelcastInstance) {

      if (hazelcastInstance != null) {
        hazelcastInstance.getLifecycleService();
        if (hazelcastInstance.getLifecycleService().isRunning()) {

          InstrumentationContext.get(ClientInvocation.class, String.class)
              .put(that, hazelcastInstance.getName());
        }
      }
    }

    public static void muzzleCheck(
        // Moved in 4.0
        ClientMapProxy proxy) {
      proxy.getServiceName();
    }
  }
}
