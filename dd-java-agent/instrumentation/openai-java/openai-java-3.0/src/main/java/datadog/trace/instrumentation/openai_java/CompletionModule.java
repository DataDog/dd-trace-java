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
      packageName + ".CompletionDecorator",
      packageName + ".OpenAiDecorator",
      packageName + ".HttpResponseWrappers",
      packageName + ".HttpResponseWrappers$DDHttpResponseFor",
      packageName + ".HttpResponseWrappers$1",
      packageName + ".HttpResponseWrappers$2",
      packageName + ".HttpResponseWrappers$2$1"
    };
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return Arrays.asList(
        new CompletionServiceAsyncInstrumentation(), new CompletionServiceInstrumentation());
  }
}
