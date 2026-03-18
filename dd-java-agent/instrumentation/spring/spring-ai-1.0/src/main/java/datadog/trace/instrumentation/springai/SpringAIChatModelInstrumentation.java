package datadog.trace.instrumentation.springai;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.springai.SpringAIDecorator.DECORATE;
import static datadog.trace.instrumentation.springai.SpringAIDecorator.SPRING_AI_REQUEST;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public class SpringAIChatModelInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  public SpringAIChatModelInstrumentation() {
    super("spring-ai");
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.springframework.ai.chat.model.ChatModel";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".SpringAIDecorator",
      packageName + ".SpringAIMessageExtractAdapter",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("call"))
            .and(takesArgument(0, named("org.springframework.ai.chat.prompt.Prompt"))),
        SpringAIChatModelInstrumentation.class.getName() + "$ChatModelCallAdvice");
  }

  public static class ChatModelCallAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(@Advice.Argument(0) final Object prompt) {
      final AgentSpan span = startSpan(SPRING_AI_REQUEST);
      DECORATE.afterStart(span);
      DECORATE.onPrompt(span, prompt);
      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
        @Advice.Enter final AgentScope scope,
        @Advice.Return final Object response,
        @Advice.Thrown final Throwable throwable) {
      if (scope == null) {
        return;
      }
      final AgentSpan span = scope.span();
      if (throwable != null) {
        DECORATE.onError(scope, throwable);
      } else {
        DECORATE.onResponse(span, response);
      }
      DECORATE.beforeFinish(scope);
      scope.close();
      span.finish();
    }
  }
}
