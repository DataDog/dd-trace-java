package datadog.trace.instrumentation.openai_java;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
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
import datadog.trace.api.llmobs.LLMObsContext;
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
      AgentSpan span = startSpan(OpenAiDecorator.INSTRUMENTATION_NAME, OpenAiDecorator.SPAN_NAME);
      // TODO get span from context?
      DECORATE.afterStart(span);
      DECORATE.withClientOptions(span, clientOptions);
      CompletionDecorator.DECORATE.withCompletionCreateParams(span, params);

      llmScope = LLMObsContext.attach(span.context());
      // TODO should the agent span be activated via the context api or keep separate?
      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(
        @Advice.Enter final AgentScope scope,
        @Advice.Return(readOnly = false) HttpResponseFor<Completion> response,
        @Advice.Thrown final Throwable err,
        @Advice.Local("llmScope") ContextScope llmScope) {
      final AgentSpan span = scope.span();
      try {
        if (err != null) {
          DECORATE.onError(span, err);
        }
        if (response != null) {
          response =
              HttpResponseWrapper.wrap(
                  response, span, CompletionDecorator.DECORATE::withCompletion);
        }
        DECORATE.beforeFinish(span);
      } finally {
        scope.close();
        llmScope.close();
        span.finish();
      }
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
      final AgentSpan span = scope.span();
      try {
        if (err != null) {
          DECORATE.onError(span, err);
        }
        if (response != null) {
          response =
              HttpStreamResponseWrapper.wrap(
                  response, span, CompletionDecorator.DECORATE::withCompletions);
        } else {
          span.finish();
        }
        DECORATE.beforeFinish(span);
      } finally {
        scope.close();
      }
    }
  }
}
