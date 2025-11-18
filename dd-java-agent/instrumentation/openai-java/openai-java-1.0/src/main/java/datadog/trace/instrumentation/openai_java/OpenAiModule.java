package datadog.trace.instrumentation.openai_java;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.Arrays;
import java.util.List;

@AutoService(InstrumenterModule.class)
public class OpenAiModule extends InstrumenterModule.Tracing {
  public OpenAiModule() {
    super("openai-java");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".OpenAiDecorator",
      packageName + ".ResponseWrappers",
      packageName + ".ResponseWrappers$DDHttpResponseFor",
      packageName + ".ResponseWrappers$1",
      packageName + ".ResponseWrappers$2",
      packageName + ".ResponseWrappers$2$1",
    };
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return Arrays.asList(
        new ChatCompletionServiceAsyncInstrumentation(),
        new ChatCompletionServiceInstrumentation(),
        new CompletionServiceAsyncInstrumentation(),
        new CompletionServiceInstrumentation(),
        new EmbeddingServiceInstrumentation(),
        new ResponseServiceAsyncInstrumentation(),
        new ResponseServiceInstrumentation());
  }
}
