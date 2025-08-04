package datadog.trace.instrumentation.openai;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.*;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;

import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.services.blocking.CompletionService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.api.llmobs.LLMObsSpan;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

public class ChatCompletionServiceInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {
  public ChatCompletionServiceInstrumentation() {
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
        "com.openai.services.blocking.ChatCompletionService", OpenAIClientInfo.class.getName());
    return contextStores;
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("create"))
            .and(
                takesArgument(
                    0, named("com.openai.models.completions.ChatCompletionCreateParams"))),
        getClass().getName() + "$ChatCompletionServiceAdvice");
  }

  @Override
  public String hierarchyMarkerType() {
    return "com.openai.services.blocking.chat.ChatCompletionService";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  public static class ChatCompletionServiceAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static Map<String, Object> methodEnter(
        @Advice.Argument(0) final ChatCompletionCreateParams params) {
      Map<String, Object> spans = new HashMap<>();
      spans.put(
          "datadog.trace.api.llmobs.LLMObsSpan",
          OpenAIClientDecorator.DECORATE.startLLMObsChatCompletionSpan(params));
      spans.put(
          "datadog.trace.bootstrap.instrumentation.api.AgentScope",
          OpenAIClientDecorator.DECORATE.startChatCompletionSpan(params));
      return spans;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void methodExit(
        @Advice.Enter final Map<String, Object> spans,
        @Advice.This final CompletionService completionService,
        @Advice.Return final Object result,
        @Advice.Thrown final Throwable throwable) {
      OpenAIClientInfo info =
          InstrumentationContext.get(CompletionService.class, OpenAIClientInfo.class)
              .get(completionService);
      OpenAIClientDecorator.DECORATE.finishLLMObsChatCompletionSpan(
          (LLMObsSpan) spans.get("datadog.trace.api.llmobs.LLMObsSpan"),
          (ChatCompletion) result,
          throwable);
      OpenAIClientDecorator.DECORATE.finishSpan(
          (AgentScope) spans.get("datadog.trace.bootstrap.instrumentation.api.AgentScope"),
          result,
          throwable);
    }
  }
}
