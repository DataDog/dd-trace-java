package datadog.trace.instrumentation.langchain4j;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.datadog.profiling.ddprof.DatadogProfilingIntegration;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

public class ToolExecutorInstrumentation
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  @Override
  public String hierarchyMarkerType() {
    return "dev.langchain4j.service.tool.ToolExecutor";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named("dev.langchain4j.service.tool.ToolExecutor"));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(net.bytebuddy.matcher.ElementMatchers.named("execute"))
            .and(takesArguments(2)),
        ToolExecutorInstrumentation.class.getName() + "$ExecuteAdvice");
  }

  public static final class ExecuteAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter() {
      DatadogProfilingIntegration.setLlmPhase("tool_execution");
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void exit() {
      DatadogProfilingIntegration.clearLlmPhase();
    }
  }
}
