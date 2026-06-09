package datadog.trace.instrumentation.langchain4j;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.jfr.llm.ToolExecutorEvent;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
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
            .and(takesArgument(0, named("dev.langchain4j.agent.tool.ToolExecutionRequest"))),
        ToolExecutorInstrumentation.class.getName() + "$ExecuteAdvice");
  }

  public static final class ExecuteAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static ToolExecutorEvent enter(@Advice.Argument(0) ToolExecutionRequest request) {
      return new ToolExecutorEvent(request.name());
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void exit(@Advice.Enter ToolExecutorEvent event) {
      if (event == null) return;
      event.end();
      if (event.shouldCommit()) {
        event.commit();
      }
    }
  }
}
