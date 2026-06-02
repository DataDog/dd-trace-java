package datadog.trace.instrumentation.langchain4j;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.Arrays;
import java.util.List;

@AutoService(InstrumenterModule.class)
public class LangChain4jProfilingModule extends InstrumenterModule.Profiling {

  public LangChain4jProfilingModule() {
    super("langchain4j");
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return Arrays.asList(
        new ChatModelInstrumentation(),
        new ToolExecutorInstrumentation(),
        new AiServicesInstrumentation());
  }
}
