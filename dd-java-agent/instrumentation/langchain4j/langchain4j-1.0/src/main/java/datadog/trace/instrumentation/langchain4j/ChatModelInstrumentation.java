package datadog.trace.instrumentation.langchain4j;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.jfr.llm.ChatModelEvent;
import dev.langchain4j.model.chat.request.ChatRequest;
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
    public static ChatModelEvent enter(@Advice.Argument(0) ChatRequest request) {
      if (request == null || request.parameters() == null) return null;
      return new ChatModelEvent(request.parameters().modelName());
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void exit(@Advice.Enter ChatModelEvent event) {
      if (event == null) return;
      event.end();
      if (event.shouldCommit()) {
        event.commit();
      }
    }
  }
}
