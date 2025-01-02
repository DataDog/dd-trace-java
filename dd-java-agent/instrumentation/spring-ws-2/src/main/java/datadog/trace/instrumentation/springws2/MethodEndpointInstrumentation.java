package datadog.trace.instrumentation.springws2;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.ErrorPriorities.UNSET;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class MethodEndpointInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public MethodEndpointInstrumentation() {
    super("spring-ws", "spring-ws-2");
  }

  @Override
  public String instrumentedType() {
    return "org.springframework.ws.server.endpoint.MethodEndpoint";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    // public Object MethodEndpoint.invoke(Object... args)
    transformer.applyAdvice(
        isMethod().and(named("invoke")),
        MethodEndpointInstrumentation.class.getName() + "$InvokeAdvice");
  }

  public static class InvokeAdvice {
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void captureUserCodeException(@Advice.Thrown final Throwable throwable) {
      if (throwable == null) {
        return;
      }
      // Try to get the root span
      AgentSpan span = activeSpan();
      if (span != null) {
        AgentSpan rootSpan = span.getLocalRootSpan();
        if (rootSpan != null) {
          span = rootSpan;
        }
        // Capture the exception without setting span as errored
        span.addThrowable(throwable, UNSET);
      }
    }
  }
}
