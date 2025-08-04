package datadog.trace.instrumentation.openai;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.*;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;

import com.openai.models.completions.CompletionCreateParams;
import com.openai.services.blocking.CompletionService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

public class CompletionServiceInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {
  public CompletionServiceInstrumentation() {
    super("openai-client");
  }

  @Override
  protected boolean defaultEnabled() {
    return InstrumenterConfig.get().isIntegrationEnabled(Collections.singleton("openai"), false);
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".OpenAIClientInfo",
    };
  }

  @Override
  public Map<String, String> contextStore() {
    Map<String, String> contextStores = new HashMap<>(1);
    contextStores.put(
        "com.openai.services.blocking.CompletionService", OpenAIClientInfo.class.getName());
    return contextStores;
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("create"))
            .and(takesArgument(0, named("com.openai.models.completions.CompletionCreateParams"))),
        getClass().getName() + "$CompletionServiceAdvice");
  }

  @Override
  public String hierarchyMarkerType() {
    return "com.openai.services.blocking.CompletionService";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  public static class CompletionServiceAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope methodEnter(@Advice.Argument(0) final CompletionCreateParams params) {

      return OpenAIClientDecorator.DECORATE.startCompletionSpan(params);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void methodExit(
        @Advice.Enter final AgentScope span,
        @Advice.This final CompletionService completionService,
        @Advice.Return final Object result,
        @Advice.Thrown final Throwable throwable) {
      OpenAIClientInfo info =
          InstrumentationContext.get(CompletionService.class, OpenAIClientInfo.class)
              .get(completionService);
      OpenAIClientDecorator.DECORATE.finishSpan(span, result, throwable);
    }
  }
}
