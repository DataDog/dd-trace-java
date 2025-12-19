package datadog.trace.instrumentation.openai_java;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.Arrays;
import java.util.List;

@AutoService(InstrumenterModule.class)
public class ResponseModule extends InstrumenterModule.Tracing {
  public ResponseModule() {
    super("openai-java");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".ResponseDecorator",
      packageName + ".FunctionCallOutputExtractor",
      packageName + ".OpenAiDecorator",
      packageName + ".HttpResponseWrapper",
      packageName + ".HttpStreamResponseWrapper",
      packageName + ".HttpStreamResponseStreamWrapper",
      packageName + ".ToolCallExtractor",
      packageName + ".ToolCallExtractor$1"
    };
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return Arrays.asList(
        new ResponseServiceAsyncInstrumentation(), new ResponseServiceInstrumentation());
  }
}
