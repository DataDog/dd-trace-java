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
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;

public class ChatCompletionServiceInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  @Override
  public String instrumentedType() {
    return "com.openai.services.blocking.chat.ChatCompletionServiceImpl$WithRawResponseImpl";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("create"))
            .and(
                takesArgument(
                    0, named("com.openai.models.chat.completions.ChatCompletionCreateParams")))
            .and(returns(named("com.openai.core.http.HttpResponseFor"))),
        getClass().getName() + "$CreateAdvice");

    transformer.applyAdvice(
        isMethod()
            .and(named("createStreaming"))
            .and(
                takesArgument(
                    0, named("com.openai.models.chat.completions.ChatCompletionCreateParams")))
            .and(returns(named("com.openai.core.http.HttpResponseFor"))),
        getClass().getName() + "$CreateStreamingAdvice");
  }

  public static class CreateAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope enter(
        @Advice.Argument(0) final ChatCompletionCreateParams params,
        @Advice.FieldValue("clientOptions") ClientOptions clientOptions) {
      AgentSpan span = startSpan(OpenAiDecorator.INSTRUMENTATION_NAME, OpenAiDecorator.SPAN_NAME);
      DECORATE.afterStart(span);
      DECORATE.decorateWithClientOptions(span, clientOptions);
      DECORATE.decorateChatCompletion(span, params);
      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(
        @Advice.Enter final AgentScope scope,
        @Advice.Return(readOnly = false) HttpResponseFor<ChatCompletion> response,
        @Advice.Thrown final Throwable err) {
      final AgentSpan span = scope.span();
      try {
        if (err != null) {
          DECORATE.onError(span, err);
        }
        if (response != null) {
          response =
              ResponseWrappers.wrapResponse(
                  response, span, OpenAiDecorator.DECORATE::decorateWithChatCompletion);
        }
        DECORATE.beforeFinish(span);
      } finally {
        scope.close();
        span.finish();
      }
    }
  }

  public static class CreateStreamingAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope enter(
        @Advice.Argument(0) final ChatCompletionCreateParams params,
        @Advice.FieldValue("clientOptions") ClientOptions clientOptions) {
      AgentSpan span = startSpan(OpenAiDecorator.INSTRUMENTATION_NAME, OpenAiDecorator.SPAN_NAME);
      DECORATE.afterStart(span);
      DECORATE.decorateWithClientOptions(span, clientOptions);
      DECORATE.decorateChatCompletion(span, params);
      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(
        @Advice.Enter final AgentScope scope,
        @Advice.Return(readOnly = false)
            HttpResponseFor<StreamResponse<ChatCompletionChunk>> response,
        @Advice.Thrown final Throwable err) {
      final AgentSpan span = scope.span();
      try {
        if (err != null) {
          DECORATE.onError(span, err);
        }
        if (response != null) {
          response =
              ResponseWrappers.wrapStreamResponse(
                  response, span, DECORATE::decorateWithChatCompletionChunks);
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
