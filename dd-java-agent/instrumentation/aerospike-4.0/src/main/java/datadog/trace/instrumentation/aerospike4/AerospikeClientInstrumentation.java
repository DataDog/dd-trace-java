package datadog.trace.instrumentation.aerospike4;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.spanFromScope;
import static datadog.trace.instrumentation.aerospike4.AerospikeClientDecorator.DECORATE;
import static net.bytebuddy.implementation.bytecode.assign.Assigner.Typing.DYNAMIC;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import datadog.context.ContextScope;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.internal.VisibleForTesting;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.matcher.ElementMatcher;

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
        asyncRequestMethods(), getClass().getName() + "$TraceAsyncRequestAdvice");
  }

  /**
   * The listener types {@link TracingListener} implements. The async advice replaces the listener
   * argument with a {@code TracingListener}, so it may only apply where one can stand in: newer
   * clients take other listener types, such as {@code BatchRecordArrayListener}, and replacing
   * those fails the advice after it has started and activated the span. Kept in sync with {@code
   * TracingListener} by a test; it can't be read from the class, which would load it here.
   */
  @VisibleForTesting
  static final String[] WRAPPABLE_LISTENERS = {
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
    "com.aerospike.client.listener.DeleteListener"
  };

  /** Async requests whose listener a {@link TracingListener} can replace. */
  @VisibleForTesting
  static ElementMatcher<MethodDescription> asyncRequestMethods() {
    return isMethod().and(isPublic()).and(takesArgument(1, namedOneOf(WRAPPABLE_LISTENERS)));
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
}
