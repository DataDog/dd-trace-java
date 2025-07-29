package datadog.trace.instrumentation.openai;

import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import com.openai.services.blocking.CompletionService;
import com.openai.services.blocking.EmbeddingService;
import com.openai.services.blocking.chat.ChatCompletionService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.InstrumentationContext;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;

public class OpenAIClientInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public OpenAIClientInstrumentation() {
    super("openai-client");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".OpenAIClientInfo",
    };
  }

  @Override
  public String instrumentedType() {
    return "com.openai.client.OpenAIClientImpl";
  }

  @Override
  public Map<String, String> contextStore() {
    Map<String, String> contextStores = new HashMap<>(3);
    contextStores.put(
        "com.openai.services.blocking.chat.ChatCompletionService",
        OpenAIClientInfo.class.getName());
    contextStores.put(
        "com.openai.services.blocking.CompletionService", OpenAIClientInfo.class.getName());
    contextStores.put(
        "com.openai.services.blocking.EmbeddingService", OpenAIClientInfo.class.getName());
    return contextStores;
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isConstructor().and(takesArgument(0, ClientOptions.class)),
        getClass().getName() + "$OpenAIClientAdvice");
  }

  public static class OpenAIClientAdvice {

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void methodExit(
        @Advice.This final OpenAIClientImpl client,
        @Advice.Argument(0) final ClientOptions options,
        @Advice.Thrown final Throwable throwable) {
      OpenAIClientInfo info = OpenAIClientInfo.fromClientOptions(options);
      if (info != null) {
        InstrumentationContext.get(CompletionService.class, OpenAIClientInfo.class)
            .put(client.completions(), info);
        InstrumentationContext.get(EmbeddingService.class, OpenAIClientInfo.class)
            .put(client.embeddings(), info);
        InstrumentationContext.get(ChatCompletionService.class, OpenAIClientInfo.class)
            .put(client.chat().completions(), info);
      }
    }
  }
}
