package datadog.trace.instrumentation.armeria.grpc.client;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.linecorp.armeria.common.grpc.protocol.ArmeriaMessageDeframer;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.grpc.ClientCall;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

public class ArmeriaMessageDeframerInstrumentation
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  @Override
  public String hierarchyMarkerType() {
    return "com.linecorp.armeria.common.grpc.protocol.ArmeriaMessageDeframer";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return named(hierarchyMarkerType()).or(extendsClass(named(hierarchyMarkerType())));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isConstructor()
            .and(
                takesArgument(
                    0,
                    named(
                        "com.linecorp.armeria.common.grpc.protocol.ArmeriaMessageDeframer$Listener"))),
        getClass().getName() + "$CaptureClientCallArg0");
    transformer.applyAdvice(
        isConstructor()
            .and(
                takesArgument(
                    2, named("com.linecorp.armeria.internal.common.grpc.TransportStatusListener"))),
        getClass().getName() + "$CaptureClientCallArg2");
    transformer.applyAdvice(
        isMethod().and(named("process").or(named("deframe"))),
        getClass().getName() + "$ActivateSpan");
  }

  public static final class CaptureClientCallArg0 {
    @SuppressWarnings("rawtypes")
    @Advice.OnMethodExit
    public static void capture(
        @Advice.This ArmeriaMessageDeframer messageDeframer,
        @Advice.Argument(0) Object clientCall) {
      if (clientCall instanceof ClientCall) {
        InstrumentationContext.get(ArmeriaMessageDeframer.class, ClientCall.class)
            .put(messageDeframer, (ClientCall) clientCall);
      }
    }
  }

  public static final class CaptureClientCallArg2 {
    @SuppressWarnings("rawtypes")
    @Advice.OnMethodExit
    public static void capture(
        @Advice.This ArmeriaMessageDeframer messageDeframer,
        @Advice.Argument(2) Object clientCall) {
      if (clientCall instanceof ClientCall) {
        InstrumentationContext.get(ArmeriaMessageDeframer.class, ClientCall.class)
            .put(messageDeframer, (ClientCall) clientCall);
      }
    }
  }

  public static final class ActivateSpan {
    @SuppressWarnings("rawtypes")
    @Advice.OnMethodEnter
    public static AgentScope before(@Advice.This ArmeriaMessageDeframer messageDeframer) {
      ClientCall clientCall =
          InstrumentationContext.get(ArmeriaMessageDeframer.class, ClientCall.class)
              .get(messageDeframer);
      if (clientCall != null) {
        AgentSpan span =
            InstrumentationContext.get(ClientCall.class, AgentSpan.class).get(clientCall);
        if (null != span) {
          return activateSpan(span);
        }
      }
      return null;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void after(@Advice.Enter AgentScope scope) {
      if (null != scope) {
        scope.close();
      }
    }
  }
}
