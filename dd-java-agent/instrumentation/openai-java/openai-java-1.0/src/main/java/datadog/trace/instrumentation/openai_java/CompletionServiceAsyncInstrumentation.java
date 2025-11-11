package datadog.trace.instrumentation.openai_java;

import com.openai.core.ClientOptions;
import com.openai.core.http.HttpResponseFor;
import com.openai.core.http.StreamResponse;
import com.openai.models.completions.Completion;
import com.openai.models.completions.CompletionCreateParams;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;

import java.util.concurrent.CompletableFuture;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.openai_java.OpenAiDecorator.DECORATE;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

public class CompletionServiceAsyncInstrumentation implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  @Override
  public String instrumentedType() {
    return "com.openai.services.async.CompletionServiceAsyncImpl$WithRawResponseImpl";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("create"))
            .and(takesArgument(0, named("com.openai.models.completions.CompletionCreateParams")))
            .and(returns(named(CompletableFuture.class.getName()))),
        getClass().getName() + "$CreateAdvice");

    transformer.applyAdvice(
        isMethod()
            .and(named("createStreaming"))
            .and(takesArgument(0, named("com.openai.models.completions.CompletionCreateParams")))
            .and(returns(named(CompletableFuture.class.getName()))),
        getClass().getName() + "$CreateStreamingAdvice");
  }

  public static class CreateAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope enter(@Advice.Argument(0) final CompletionCreateParams params, @Advice.FieldValue("clientOptions") ClientOptions clientOptions) {
      AgentSpan span = startSpan(OpenAiDecorator.INSTRUMENTATION_NAME, OpenAiDecorator.SPAN_NAME);
      DECORATE.afterStart(span);
      DECORATE.decorateWithClientOptions(span, clientOptions);
      DECORATE.decorateCompletion(span, params);
      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(@Advice.Enter final AgentScope scope, @Advice.Return(readOnly = false) CompletableFuture<HttpResponseFor<Completion>> future, @Advice.Thrown final Throwable err) {
      final AgentSpan span = scope.span();
      try {
        if (err != null) {
          DECORATE.onError(span, err);
        }
        if (future != null) {
          future = ResponseWrappers.wrapFutureResponse(future, span, DECORATE::decorateWithCompletion);
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
    public static AgentScope enter(@Advice.Argument(0) final CompletionCreateParams params, @Advice.FieldValue("clientOptions") ClientOptions clientOptions) {
      AgentSpan span = startSpan(OpenAiDecorator.INSTRUMENTATION_NAME, OpenAiDecorator.SPAN_NAME);
      DECORATE.afterStart(span);
      DECORATE.decorateWithClientOptions(span, clientOptions);
      DECORATE.decorateCompletion(span, params);
      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(@Advice.Enter final AgentScope scope, @Advice.Return(readOnly = false) CompletableFuture<HttpResponseFor<StreamResponse<Completion>>> future, @Advice.Thrown final Throwable err) {
      final AgentSpan span = scope.span();
      try {
        if (err != null) {
          DECORATE.onError(span, err);
        }
        if (future != null) {
          future = ResponseWrappers.wrapFutureStreamResponse(future, span);
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
