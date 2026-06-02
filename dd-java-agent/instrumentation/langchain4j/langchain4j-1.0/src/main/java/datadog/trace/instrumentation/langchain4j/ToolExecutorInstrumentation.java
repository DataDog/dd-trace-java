package datadog.trace.instrumentation.langchain4j;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.profiling.Profiling;
import datadog.trace.api.profiling.ProfilingScope;
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
    public static ProfilingScope enter() {
      ProfilingScope scope = Profiling.get().newScope();
      scope.setContextValue("llm.agent.phase", "tool_execution");
      return scope;
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void exit(@Advice.Enter final ProfilingScope scope) {
      scope.clearContextValue("llm.agent.phase");
      scope.close();
    }
  }
}
