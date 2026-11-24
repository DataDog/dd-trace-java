package datadog.trace.instrumentation.aerospike6;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.aerospike4.AerospikeClientDecorator.DECORATE;
import static datadog.trace.instrumentation.aerospike4.AerospikeClientDecorator.JAVA_AEROSPIKE;
import static datadog.trace.instrumentation.aerospike4.AerospikeClientDecorator.OPERATION_NAME;
import static net.bytebuddy.implementation.bytecode.assign.Assigner.Typing.DYNAMIC;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.context.ContextContinuation;
import datadog.context.ContextScope;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public final class AerospikeBatchInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public AerospikeBatchInstrumentation() {
    super("aerospike");
  }

  @Override
  public String muzzleDirective() {
    return "aerospike-6.0";
  }

  @Override
  public String instrumentedType() {
    return "com.aerospike.client.AerospikeClient";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      "datadog.trace.instrumentation.aerospike4.AerospikeClientDecorator",
      packageName + ".BatchTracingListener",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
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

  public static final class TraceAsyncBatchRequestAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void beginRequest(
        @Advice.Origin("#m") final String methodName,
        @Advice.Argument(value = 1, readOnly = false, typing = DYNAMIC) Object listener,
        @Advice.Local("clientSpan") AgentSpan clientSpan,
        @Advice.Local("scope") ContextScope scope,
        @Advice.Local("continuation") ContextContinuation continuation,
        @Advice.Local("batch") BatchTracingListener batch) {
      final Object originalListener = listener;
      try {
        clientSpan = startSpan(JAVA_AEROSPIKE.toString(), OPERATION_NAME);
        DECORATE.afterStart(clientSpan);
        DECORATE.withMethod(clientSpan, methodName);
        scope = activateSpan(clientSpan);
        continuation = clientSpan.captureWithContext();
        batch = new BatchTracingListener(clientSpan, continuation, originalListener);
        listener = batch;
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
