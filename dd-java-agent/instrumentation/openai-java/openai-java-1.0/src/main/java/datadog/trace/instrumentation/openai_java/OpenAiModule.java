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
        packageName + ".StreamHelpers",
        packageName + ".StreamHelpers$1",
        packageName + ".StreamHelpers$1$1",
    };
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return Arrays.asList(
        new CompletionServiceInstrumentation()
    );
  }
}
