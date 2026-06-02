package datadog.trace.instrumentation.langchain4j;

import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.named;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.profiling.Profiling;
import datadog.trace.api.profiling.ProfilingScope;
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
    public static ProfilingScope enter() {
      ProfilingScope scope = Profiling.get().newScope();
      scope.setContextValue("llm.agent.phase", "context_build");
      return scope;
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void exit(@Advice.Enter final ProfilingScope scope) {
      scope.clearContextValue("llm.agent.phase");
      scope.close();
    }
  }
}
