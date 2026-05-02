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
import com.openai.models.embeddings.CreateEmbeddingResponse;
import com.openai.models.embeddings.EmbeddingCreateParams;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

public class EmbeddingServiceInstrumentation
    implements Instrumenter.ForSingleType,
        Instrumenter.HasMethodAdvice,
        Instrumenter.WithTypeStructure {
  @Override
  public String instrumentedType() {
    return "com.openai.services.blocking.EmbeddingServiceImpl$WithRawResponseImpl";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("create"))
            .and(takesArgument(0, named("com.openai.models.embeddings.EmbeddingCreateParams")))
            .and(returns(named("com.openai.core.http.HttpResponseFor"))),
        getClass().getName() + "$CreateAdvice");
  }

  @Override
  public ElementMatcher<TypeDescription> structureMatcher() {
    return declaresField(named("clientOptions"));
  }

  public static class CreateAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope enter(
        @Advice.Argument(0) final EmbeddingCreateParams params,
        @Advice.FieldValue("clientOptions") ClientOptions clientOptions) {
      AgentSpan span = DECORATE.startSpan(clientOptions);
      EmbeddingDecorator.DECORATE.withEmbeddingCreateParams(span, params);
      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(
        @Advice.Enter final AgentScope scope,
        @Advice.Return(readOnly = false) HttpResponseFor<CreateEmbeddingResponse> response,
        @Advice.Thrown final Throwable err) {
      AgentSpan span = scope.span();
      if (err != null || response == null) {
        DECORATE.finishSpan(span, err);
      } else {
        response =
            HttpResponseWrapper.wrap(
                response, span, EmbeddingDecorator.DECORATE::withCreateEmbeddingResponse);
      }
      scope.close();
    }
  }
}
