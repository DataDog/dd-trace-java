package datadog.trace.instrumentation.openai_java;

import com.openai.models.completions.Completion;
import com.openai.models.completions.CompletionCreateParams;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.llmobs.LLMObs;
import datadog.trace.api.llmobs.LLMObsSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.openai_java.OpenAiDecorator.DECORATE;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

public class CompletionServiceInstrumentation implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  @Override
  public String instrumentedType() {
    return "com.openai.services.blocking.CompletionServiceImpl";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("create"))
            .and(takesArgument(0, named("com.openai.models.completions.CompletionCreateParams"))),
        getClass().getName() + "$CreateAdvice");
  }

  public static class CreateAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope enter(@Advice.Argument(0) final CompletionCreateParams params, @Advice.Local("llmSpan") LLMObsSpan llmObsSpan) {
      AgentSpan span = startSpan(OpenAiDecorator.INSTRUMENTATION_NAME, OpenAiDecorator.SPAN_NAME);
      DECORATE.afterStart(span);
      DECORATE.decorate(span, params);
      String mlApp = "mlApp"; //Config.get().getLlmObsMlApp(); // TODO
      String mlProvider = "openai"; // TODO
      llmObsSpan =
          LLMObs.startLLMSpan("OpenAI.createCompletion", params.model().toString(), mlProvider,mlApp, null);
      // TODO decorate llmObsSpan (annotate I/O and set metadata, etc

      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(@Advice.Enter final AgentScope scope, @Advice.Return Completion result, @Advice.Thrown final Throwable err, @Advice.Local("llmSpan") LLMObsSpan llmObsSpan) {
      final AgentSpan span = scope.span();
      if (err != null) {
        DECORATE.onError(span, err);
      }
      if (result != null) {
        DECORATE.decorate(span, result);
      }
      DECORATE.beforeFinish(span);
      scope.close();
      span.finish();
      if (llmObsSpan != null) {
        // TODO decorate llmObsSpan
        llmObsSpan.finish();
      }
    }
  }
}
