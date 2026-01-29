package datadog.trace.instrumentation.openai_java;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.Arrays;
import java.util.List;

@AutoService(InstrumenterModule.class)
public class CompletionModule extends InstrumenterModule.Tracing {
  public CompletionModule() {
    super("openai-java");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".CommonTags",
      packageName + ".CompletionDecorator",
      packageName + ".OpenAiDecorator",
      packageName + ".HttpResponseWrapper",
      packageName + ".HttpStreamResponseWrapper",
      packageName + ".HttpStreamResponseStreamWrapper",
    };
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return Arrays.asList(
        new CompletionServiceAsyncInstrumentation(), new CompletionServiceInstrumentation());
  }
}
