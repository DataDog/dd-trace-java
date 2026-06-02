package datadog.trace.instrumentation.langchain4j;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.profiling.Profiling;
import datadog.trace.api.profiling.ProfilingScope;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

public class ChatModelInstrumentation
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  @Override
  public String hierarchyMarkerType() {
    return "dev.langchain4j.model.chat.ChatModel";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named("dev.langchain4j.model.chat.ChatModel"));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(net.bytebuddy.matcher.ElementMatchers.named("chat"))
            .and(takesArgument(0, named("dev.langchain4j.model.chat.request.ChatRequest"))),
        ChatModelInstrumentation.class.getName() + "$ChatAdvice");
  }

  public static final class ChatAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static ProfilingScope enter() {
      ProfilingScope scope = Profiling.get().newScope();
      scope.setContextValue("llm.agent.phase", "awaiting_inference");
      return scope;
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void exit(@Advice.Enter final ProfilingScope scope) {
      scope.clearContextValue("llm.agent.phase");
      scope.close();
    }
  }
}
