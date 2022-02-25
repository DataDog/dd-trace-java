package datadog.trace.instrumentation.aerospike4;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.instrumentation.aerospike4.AerospikeClientDecorator.DECORATE;
import static net.bytebuddy.implementation.bytecode.assign.Assigner.Typing.DYNAMIC;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public final class AerospikeClientInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {
  public AerospikeClientInstrumentation() {
    super("aerospike");
  }

  @Override
  public String instrumentedType() {
    return "com.aerospike.client.AerospikeClient";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".AerospikeClientDecorator", packageName + ".TracingListener",
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(takesArgument(0, nameStartsWith("com.aerospike.client.policy"))),
        getClass().getName() + "$TraceSyncRequestAdvice");
    transformation.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(takesArgument(1, nameStartsWith("com.aerospike.client.listener"))),
        getClass().getName() + "$TraceAsyncRequestAdvice");
  }

  public static final class TraceSyncRequestAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope beginRequest(@Advice.Origin("#m") final String methodName) {
      AgentSpan clientSpan = DECORATE.startAerospikeSpan(methodName);
      return activateSpan(clientSpan);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exitRequest(
        @Advice.Enter final AgentScope scope, @Advice.Thrown final Throwable error) {
      DECORATE.finishAerospikeSpan(scope.span(), error);
      scope.close();
    }
  }

  public static final class TraceAsyncRequestAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope beginRequest(
        @Advice.Origin("#m") final String methodName,
        @Advice.Argument(value = 1, readOnly = false, typing = DYNAMIC) Object listener) {
      AgentSpan clientSpan = DECORATE.startAerospikeSpan(methodName);
      AgentScope scope = activateSpan(clientSpan);
      // always want to wrap even when there's no listener so we get the true async time
      listener = new TracingListener(clientSpan, scope.capture(), listener);
      return scope;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exitRequest(
        @Advice.Enter final AgentScope scope, @Advice.Thrown final Throwable error) {
      if (error != null) {
        DECORATE.finishAerospikeSpan(scope.span(), error);
      } else {
        // span will be finished in the traced listener
      }
      scope.close();
    }
  }
}
