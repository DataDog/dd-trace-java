package datadog.trace.instrumentation.springai;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.springai.SpringAIDecorator.DECORATE;
import static datadog.trace.instrumentation.springai.SpringAIDecorator.SPRING_AI_REQUEST;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public class SpringAIChatClientCallResponseSpecInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  public SpringAIChatClientCallResponseSpecInstrumentation() {
    super("spring-ai");
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.springframework.ai.chat.client.ChatClient$CallResponseSpec";
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
            .and(takesNoArguments())
            .and(named("content").or(named("chatResponse"))),
        SpringAIChatClientCallResponseSpecInstrumentation.class.getName() + "$CallResponseAdvice");
  }

  public static class CallResponseAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentSpan onEnter(@Advice.This final Object callResponseSpec) {
      final AgentSpan span = startSpan(SPRING_AI_REQUEST);
      DECORATE.afterStart(span);
      DECORATE.onPrompt(span, callResponseSpec);
      return span;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
        @Advice.Enter final AgentSpan span,
        @Advice.Return final Object output,
        @Advice.Thrown final Throwable throwable) {
      if (span == null) {
        return;
      }
      if (throwable != null) {
        DECORATE.onError(span, throwable);
      } else if (output instanceof CharSequence) {
        DECORATE.onOutput(span, output.toString());
      } else {
        DECORATE.onResponse(span, output);
      }
      DECORATE.beforeFinish(span);
      span.finish();
    }
  }
}
