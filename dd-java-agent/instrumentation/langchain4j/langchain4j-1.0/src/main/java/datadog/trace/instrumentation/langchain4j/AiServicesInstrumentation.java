package datadog.trace.instrumentation.langchain4j;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.jfr.llm.AiServiceEvent;
import java.lang.reflect.Method;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

public class AiServicesInstrumentation
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  @Override
  public String hierarchyMarkerType() {
    return "java.lang.reflect.InvocationHandler";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named("java.lang.reflect.InvocationHandler"))
        .and(nameStartsWith("dev.langchain4j.service.DefaultAiServices$"));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            // FQN used to disambiguate from
            // datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named
            .and(net.bytebuddy.matcher.ElementMatchers.named("invoke"))
            .and(net.bytebuddy.matcher.ElementMatchers.takesArguments(3))
            .and(
                net.bytebuddy.matcher.ElementMatchers.takesArgument(
                    1, named("java.lang.reflect.Method"))),
        AiServicesInstrumentation.class.getName() + "$InvokeAdvice");
  }

  public static final class InvokeAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AiServiceEvent enter(@Advice.Argument(1) Method method) {
      if (method == null) return null;
      if (method.getDeclaringClass() == Object.class) return null;
      // Skip streaming/async return types that return before LLM work completes
      Class<?> returnType = method.getReturnType();
      if (returnType.getName().contains("TokenStream")
          || returnType.getName().contains("CompletableFuture")) return null;
      return new AiServiceEvent(method.getDeclaringClass().getSimpleName(), method.getName());
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void exit(@Advice.Enter AiServiceEvent event) {
      if (event == null) return;
      event.end();
      if (event.shouldCommit()) {
        event.commit();
      }
    }
  }
}
