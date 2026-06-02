package datadog.trace.instrumentation.langchain4j;

import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.named;

import com.datadog.profiling.ddprof.DatadogProfilingIntegration;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.asm.Advice;

public class AiServicesInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  @Override
  public String instrumentedType() {
    return "dev.langchain4j.service.DefaultAiServices$1";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("invoke")),
        AiServicesInstrumentation.class.getName() + "$InvokeAdvice");
  }

  public static final class InvokeAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter() {
      DatadogProfilingIntegration.setLlmPhase("context_build");
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void exit() {
      DatadogProfilingIntegration.clearLlmPhase();
    }
  }
}
