package datadog.trace.instrumentation.springws2;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.ErrorPriorities;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public class MethodEndpointInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {
  public MethodEndpointInstrumentation() {
    super("spring-ws", "spring-ws-2");
  }

  @Override
  public String instrumentedType() {
    return "org.springframework.ws.server.endpoint.MethodEndpoint";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    // public Object MethodEndpoint.invoke(Object... args)
    transformation.applyAdvice(
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
        // Check if the span is already in error
        boolean error = span.isError();
        // Capture the exception
        span.addThrowable(throwable);
        // Restore the error state using UNSET priority to not override prior decision
        if (!error) {
          span.setError(false, ErrorPriorities.UNSET);
        }
      }
    }
  }
}
