package datadog.trace.instrumentation.openai;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import com.openai.models.embeddings.EmbeddingCreateParams;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public class EmbeddingServiceInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  public EmbeddingServiceInstrumentation() {
    super("openai", "openai-java");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".OpenAIDecorator",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    // Instrument embedding creation methods
    transformer.applyAdvice(
        isMethod()
            .and(named("create"))
            .and(isPublic())
            .and(takesArgument(0, named("com.openai.models.embeddings.EmbeddingCreateParams"))),
        EmbeddingServiceInstrumentation.class.getName() + "$EmbeddingAdvice");
  }

  @Override
  public String hierarchyMarkerType() {
    return "com.openai.services.embedding.EmbeddingService";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  public static class EmbeddingAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(
        @Advice.Argument(0) final EmbeddingCreateParams embeddingParams) {

      return OpenAIClientDecorator.DECORATE.startEmbeddingSpan(embeddingParams);
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void onExit(
        @Advice.Enter final AgentScope span,
        @Advice.Return final Object result,
        @Advice.Thrown final Throwable throwable) {

      OpenAIClientDecorator.DECORATE.finishSpan(span, result, throwable);
    }
  }
}
