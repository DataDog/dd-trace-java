package datadog.trace.instrumentation.grpc.client;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.instrumentation.grpc.client.GrpcClientDecorator.DECORATE;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.MethodDescriptor;
import java.util.Collections;
import java.util.Map;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public class ManagedChannelImplInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {
  public ManagedChannelImplInstrumentation() {
    super("grpc", "grpc-client");
  }

  @Override
  public String instrumentedType() {
    return "io.grpc.internal.ManagedChannelImpl";
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap("io.grpc.ClientCall", AgentSpan.class.getName());
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".GrpcClientDecorator",
      packageName + ".GrpcClientDecorator$1",
      packageName + ".GrpcInjectAdapter"
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(
                named("newCall")
                    .and(takesArgument(0, named("io.grpc.MethodDescriptor")))
                    .and(takesArgument(1, named("io.grpc.CallOptions")))),
        getClass().getName() + "$NewCall");
  }

  public static final class NewCall {
    @Advice.OnMethodEnter
    public static AgentScope enter(
        @Advice.Argument(0) MethodDescriptor<?, ?> method,
        @Advice.Argument(1) CallOptions callOptions,
        @Advice.Local("$$ddSpan") AgentSpan span) {
      // TODO could take some interesting attributes from the CallOptions here
      //  e.g. the deadline or compressor name
      span = DECORATE.startCall(method);
      if (span != null) {
        return AgentTracer.activateSpan(span);
      }
      return null;
    }

    @Advice.OnMethodExit
    public static void exit(
        @Advice.Enter AgentScope scope,
        @Advice.Return ClientCall<?, ?> call,
        @Advice.Local("$$ddSpan") AgentSpan span) {
      if (span != null) {
        InstrumentationContext.get(ClientCall.class, AgentSpan.class).put(call, span);
      }
      if (scope != null) {
        scope.close();
      }
    }
  }
}
