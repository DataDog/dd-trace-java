package datadog.trace.instrumentation.langchain4j;

import static datadog.trace.agent.tooling.InstrumenterModule.TargetSystem.LLMOBS;
import static datadog.trace.agent.tooling.InstrumenterModule.TargetSystem.PROFILING;
import static datadog.trace.agent.tooling.InstrumenterModule.TargetSystem.TRACING;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

@AutoService(InstrumenterModule.class)
public class LangChain4jProfilingModule extends InstrumenterModule {

  public LangChain4jProfilingModule() {
    super("langchain4j");
  }

  @Override
  public boolean isApplicable(Set<TargetSystem> enabledSystems) {
    return enabledSystems.contains(TRACING)
        || enabledSystems.contains(PROFILING)
        || enabledSystems.contains(LLMOBS);
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".LangChain4jLlmObsIntegration"};
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return Arrays.asList(
        new ChatModelInstrumentation(),
        new ToolExecutorInstrumentation(),
        new AiServicesInstrumentation());
  }
}
