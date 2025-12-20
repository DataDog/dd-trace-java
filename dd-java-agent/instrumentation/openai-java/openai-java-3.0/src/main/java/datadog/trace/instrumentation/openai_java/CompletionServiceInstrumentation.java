package datadog.trace.instrumentation.openai_java;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.instrumentation.openai_java.OpenAiDecorator.DECORATE;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.openai.core.ClientOptions;
import com.openai.core.http.HttpResponseFor;
import com.openai.core.http.StreamResponse;
import com.openai.models.completions.Completion;
import com.openai.models.completions.CompletionCreateParams;
import datadog.context.ContextScope;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;

public class CompletionServiceInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  @Override
  public String instrumentedType() {
    return "com.openai.services.blocking.CompletionServiceImpl$WithRawResponseImpl";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("create"))
            .and(takesArgument(0, named("com.openai.models.completions.CompletionCreateParams")))
            .and(returns(named("com.openai.core.http.HttpResponseFor"))),
        getClass().getName() + "$CreateAdvice");

    transformer.applyAdvice(
        isMethod()
            .and(named("createStreaming"))
            .and(takesArgument(0, named("com.openai.models.completions.CompletionCreateParams")))
            .and(returns(named("com.openai.core.http.HttpResponseFor"))),
        getClass().getName() + "$CreateStreamingAdvice");
  }

  public static class CreateAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope enter(
        @Advice.Argument(0) final CompletionCreateParams params,
        @Advice.FieldValue("clientOptions") ClientOptions clientOptions,
        @Advice.Local("llmScope") ContextScope llmScope) {
      AgentSpan span = DECORATE.startSpan(clientOptions);
      // llmScope = LLMObsContext.attach(span.context());
      // TODO why would we ever need to activate llmScope in this instrumentation if we never expect
      // inner llmobs spans
      CompletionDecorator.DECORATE.withCompletionCreateParams(span, params);
      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(
        @Advice.Enter final AgentScope scope,
        @Advice.Local("llmScope") ContextScope llmScope,
        @Advice.Return(readOnly = false) HttpResponseFor<Completion> response,
        @Advice.Thrown final Throwable err) {
      AgentSpan span = scope.span();
      if (err != null || response == null) {
        DECORATE.finishSpan(span, err);
      } else {
        response =
            HttpResponseWrapper.wrap(response, span, CompletionDecorator.DECORATE::withCompletion);
      }
      scope.close();
      // llmScope.close();
    }
  }

  public static class CreateStreamingAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope enter(
        @Advice.Argument(0) final CompletionCreateParams params,
        @Advice.FieldValue("clientOptions") ClientOptions clientOptions) {
      AgentSpan span = DECORATE.startSpan(clientOptions);
      CompletionDecorator.DECORATE.withCompletionCreateParams(span, params);
      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(
        @Advice.Enter final AgentScope scope,
        @Advice.Return(readOnly = false) HttpResponseFor<StreamResponse<Completion>> response,
        @Advice.Thrown final Throwable err) {
      AgentSpan span = scope.span();
      if (err != null || response == null) {
        DECORATE.finishSpan(span, err);
      } else {
        response =
            HttpStreamResponseWrapper.wrap(
                response, span, CompletionDecorator.DECORATE::withCompletions);
      }
      scope.close();
    }
  }
}
