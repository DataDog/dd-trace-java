package datadog.trace.instrumentation.openai_java;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.declaresField;
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
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.concurrent.CompletableFuture;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

public class CompletionServiceAsyncInstrumentation
    implements Instrumenter.ForSingleType,
        Instrumenter.HasMethodAdvice,
        Instrumenter.WithTypeStructure {
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

  @Override
  public ElementMatcher<TypeDescription> structureMatcher() {
    return declaresField(named("clientOptions"));
  }

  public static class CreateAdvice {
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
        @Advice.Return(readOnly = false) CompletableFuture<HttpResponseFor<Completion>> future,
        @Advice.Thrown final Throwable err) {
      AgentSpan span = scope.span();
      if (err != null || future == null) {
        DECORATE.finishSpan(span, err);
      } else {
        future =
            HttpResponseWrapper.wrapFuture(
                future, span, CompletionDecorator.DECORATE::withCompletion);
      }
      scope.close();
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
        @Advice.Return(readOnly = false)
            CompletableFuture<HttpResponseFor<StreamResponse<Completion>>> future,
        @Advice.Thrown final Throwable err) {
      AgentSpan span = scope.span();
      if (err != null || future == null) {
        DECORATE.finishSpan(span, err);
      } else {
        future =
            HttpStreamResponseWrapper.wrapFuture(
                future, span, CompletionDecorator.DECORATE::withCompletions);
      }
      scope.close();
    }
  }
}
