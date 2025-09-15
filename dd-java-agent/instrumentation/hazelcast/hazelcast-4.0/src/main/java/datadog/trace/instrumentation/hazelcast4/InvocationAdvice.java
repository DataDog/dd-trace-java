package datadog.trace.instrumentation.hazelcast4;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.hazelcast4.HazelcastDecorator.DECORATE;

import com.hazelcast.client.impl.protocol.ClientMessage;
import com.hazelcast.client.impl.proxy.ClientMapProxy;
import com.hazelcast.client.impl.spi.impl.ClientInvocation;
import com.hazelcast.client.impl.spi.impl.ClientInvocationFuture;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;

/** Advice for instrumenting distributed object client proxy classes. */
public class InvocationAdvice {

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

    final AgentSpan span = startSpan(HazelcastConstants.SPAN_NAME);

    span.setTag(
        HazelcastConstants.HAZELCAST_INSTANCE,
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
