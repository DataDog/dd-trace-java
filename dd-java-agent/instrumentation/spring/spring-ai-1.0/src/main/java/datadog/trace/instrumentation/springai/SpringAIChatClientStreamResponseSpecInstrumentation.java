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
public class SpringAIChatClientStreamResponseSpecInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  public SpringAIChatClientStreamResponseSpecInstrumentation() {
    super("spring-ai");
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.springframework.ai.chat.client.ChatClient$StreamResponseSpec";
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
      packageName + ".SpringAIStreamWrapHelper",
      packageName + ".SpringAIStreamWrapHelper$StreamState",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(isPublic()).and(named("content")).and(takesNoArguments()),
        SpringAIChatClientStreamResponseSpecInstrumentation.class.getName()
            + "$StreamResponseAdvice");
  }

  public static class StreamResponseAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentSpan onEnter(@Advice.This final Object streamResponseSpec) {
      final AgentSpan span = startSpan(SPRING_AI_REQUEST);
      DECORATE.afterStart(span);
      DECORATE.onPrompt(span, streamResponseSpec);
      return span;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
        @Advice.Enter final AgentSpan span,
        @Advice.Return(readOnly = false) Object publisher,
        @Advice.Thrown final Throwable throwable) {
      if (span == null) {
        return;
      }
      if (throwable != null) {
        DECORATE.onError(span, throwable);
        DECORATE.beforeFinish(span);
        span.finish();
        return;
      }
      publisher = SpringAIStreamWrapHelper.wrapPublisher(publisher, span);
      if (publisher == null) {
        DECORATE.beforeFinish(span);
        span.finish();
      }
    }
  }
}
