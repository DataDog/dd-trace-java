package datadog.trace.instrumentation.aerospike4;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.spanFromScope;
import static datadog.trace.instrumentation.aerospike4.AerospikeClientDecorator.DECORATE;
import static datadog.trace.instrumentation.aerospike4.AerospikeClientDecorator.JAVA_AEROSPIKE;
import static datadog.trace.instrumentation.aerospike4.AerospikeClientDecorator.OPERATION_NAME;
import static net.bytebuddy.implementation.bytecode.assign.Assigner.Typing.DYNAMIC;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import datadog.context.ContextContinuation;
import datadog.context.ContextScope;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.lang.reflect.Method;
import net.bytebuddy.asm.Advice;

public final class AerospikeClientInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  @Override
  public String instrumentedType() {
    return "com.aerospike.client.AerospikeClient";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(takesArgument(0, nameStartsWith("com.aerospike.client.policy"))),
        getClass().getName() + "$TraceSyncRequestAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(
                takesArgument(
                    1,
                    namedOneOf(
                        "com.aerospike.client.listener.ExistsListener",
                        "com.aerospike.client.listener.ExistsSequenceListener",
                        "com.aerospike.client.listener.ExistsArrayListener",
                        "com.aerospike.client.listener.RecordListener",
                        "com.aerospike.client.listener.RecordSequenceListener",
                        "com.aerospike.client.listener.RecordArrayListener",
                        "com.aerospike.client.listener.BatchSequenceListener",
                        "com.aerospike.client.listener.BatchListListener",
                        "com.aerospike.client.listener.WriteListener",
                        "com.aerospike.client.listener.ExecuteListener",
                        "com.aerospike.client.listener.DeleteListener"))),
        getClass().getName() + "$TraceAsyncRequestAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(
                takesArgument(
                    1,
                    namedOneOf(
                        "com.aerospike.client.listener.BatchRecordArrayListener",
                        "com.aerospike.client.listener.BatchRecordSequenceListener",
                        "com.aerospike.client.listener.BatchOperateListListener"))),
        getClass().getName() + "$TraceAsyncBatchRequestAdvice");
  }

  public static final class TraceSyncRequestAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static ContextScope beginRequest(@Advice.Origin("#m") final String methodName) {
      AgentSpan clientSpan = DECORATE.startAerospikeSpan(methodName);
      return activateSpan(clientSpan);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exitRequest(
        @Advice.Enter final ContextScope scope, @Advice.Thrown final Throwable error) {
      DECORATE.finishAerospikeSpan(spanFromScope(scope), error);
      scope.close();
    }
  }

  public static final class TraceAsyncRequestAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static ContextScope beginRequest(
        @Advice.Origin("#m") final String methodName,
        @Advice.Argument(value = 1, readOnly = false, typing = DYNAMIC) Object listener) {
      AgentSpan clientSpan = DECORATE.startAerospikeSpan(methodName);
      ContextScope scope = activateSpan(clientSpan);
      // always want to wrap even when there's no listener so we get the true async time
      listener = new TracingListener(clientSpan, clientSpan.captureWithContext(), listener);
      return scope;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exitRequest(
        @Advice.Enter final ContextScope scope, @Advice.Thrown final Throwable error) {
      if (error != null) {
        DECORATE.finishAerospikeSpan(spanFromScope(scope), error);
      } else {
        // span will be finished in the traced listener
      }
      scope.close();
    }
  }

  public static final class TraceAsyncBatchRequestAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void beginRequest(
        @Advice.Origin final Method method,
        @Advice.Argument(value = 1, readOnly = false, typing = DYNAMIC) Object listener,
        @Advice.Local("clientSpan") AgentSpan clientSpan,
        @Advice.Local("scope") ContextScope scope,
        @Advice.Local("continuation") ContextContinuation continuation,
        @Advice.Local("batch") BatchTracingListener batch) {
      final Object originalListener = listener;
      try {
        final Class<?> listenerType = method.getParameterTypes()[1];
        BatchTracingListener.validate(listenerType);
        clientSpan = startSpan(JAVA_AEROSPIKE.toString(), OPERATION_NAME);
        DECORATE.afterStart(clientSpan);
        DECORATE.withMethod(clientSpan, method.getName());
        scope = activateSpan(clientSpan);
        continuation = clientSpan.captureWithContext();
        batch = new BatchTracingListener(clientSpan, continuation, originalListener);
        listener = batch.proxy(listenerType);
      } catch (final Throwable ignored) {
        listener = originalListener;
        try {
          BatchTracingListener.closeSubmission(scope, batch, continuation, clientSpan, null, true);
        } finally {
          scope = null;
          batch = null;
          continuation = null;
          clientSpan = null;
        }
      }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exitRequest(
        @Advice.Local("clientSpan") final AgentSpan clientSpan,
        @Advice.Local("scope") final ContextScope scope,
        @Advice.Local("continuation") final ContextContinuation continuation,
        @Advice.Local("batch") final BatchTracingListener batch,
        @Advice.Thrown final Throwable error) {
      BatchTracingListener.closeSubmission(
          scope, batch, continuation, clientSpan, error, error != null);
    }
  }
}
