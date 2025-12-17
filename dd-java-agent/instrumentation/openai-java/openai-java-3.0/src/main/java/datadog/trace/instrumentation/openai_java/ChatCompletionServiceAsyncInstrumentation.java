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
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.concurrent.CompletableFuture;
import net.bytebuddy.asm.Advice;

public class ChatCompletionServiceAsyncInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  @Override
  public String instrumentedType() {
    return "com.openai.services.async.chat.ChatCompletionServiceAsyncImpl$WithRawResponseImpl";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("create"))
            .and(
                takesArgument(
                    0, named("com.openai.models.chat.completions.ChatCompletionCreateParams")))
            .and(returns(named(CompletableFuture.class.getName()))),
        getClass().getName() + "$CreateAdvice");

    transformer.applyAdvice(
        isMethod()
            .and(named("createStreaming"))
            .and(
                takesArgument(
                    0, named("com.openai.models.chat.completions.ChatCompletionCreateParams")))
            .and(returns(named(CompletableFuture.class.getName()))),
        getClass().getName() + "$CreateStreamingAdvice");
  }

  public static class CreateAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope enter(
        @Advice.Argument(0) final ChatCompletionCreateParams params,
        @Advice.FieldValue("clientOptions") ClientOptions clientOptions) {
      AgentSpan span = DECORATE.startSpan(clientOptions);
      ChatCompletionDecorator.DECORATE.withChatCompletionCreateParams(span, params, false);
      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(
        @Advice.Enter final AgentScope scope,
        @Advice.Return(readOnly = false) CompletableFuture<HttpResponseFor<ChatCompletion>> future,
        @Advice.Thrown final Throwable err) {
      final AgentSpan span = scope.span();
      try {
        if (err != null) {
          DECORATE.onError(span, err);
        }
        if (future != null) {
          future =
              ResponseWrappers.wrapFutureResponse(
                  future, span, ChatCompletionDecorator.DECORATE::withChatCompletion);
        } else {
          span.finish();
        }
      } finally {
        scope.close();
      }
    }
  }

  public static class CreateStreamingAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope enter(
        @Advice.Argument(0) final ChatCompletionCreateParams params,
        @Advice.FieldValue("clientOptions") ClientOptions clientOptions) {
      AgentSpan span = DECORATE.startSpan(clientOptions);
      ChatCompletionDecorator.DECORATE.withChatCompletionCreateParams(span, params, true);
      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(
        @Advice.Enter final AgentScope scope,
        @Advice.Return(readOnly = false)
            CompletableFuture<HttpResponseFor<StreamResponse<ChatCompletionChunk>>> future,
        @Advice.Thrown final Throwable err) {
      final AgentSpan span = scope.span();
      try {
        if (err != null) {
          DECORATE.onError(span, err);
        }
        if (future != null) {
          future =
              ResponseWrappers.wrapFutureStreamResponse(
                  future, span, ChatCompletionDecorator.DECORATE::withChatCompletionChunks);
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
