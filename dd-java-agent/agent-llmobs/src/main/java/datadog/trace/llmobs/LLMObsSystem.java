package datadog.trace.llmobs;

import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.trace.api.Config;
import datadog.trace.api.llmobs.LLMObs;
import datadog.trace.api.llmobs.LLMObsSpan;
import datadog.trace.api.llmobs.LLMObsTags;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.llmobs.domain.DDLLMObsSpan;
import datadog.trace.llmobs.domain.LLMObsInternal;
import java.lang.instrument.Instrumentation;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LLMObsSystem {

  private static final Logger LOGGER = LoggerFactory.getLogger(LLMObsSystem.class);

  private static final String CUSTOM_MODEL_VAL = "custom";

  public static void start(Instrumentation inst, SharedCommunicationObjects sco) {
    Config config = Config.get();
    if (!config.isLlmObsEnabled()) {
      LOGGER.debug("LLM Observability is disabled");
      return;
    }

    sco.createRemaining(config);

    LLMObsInternal.setLLMObsSpanFactory(
        new LLMObsManualSpanFactory(config.getLlmObsMlApp(), config.getServiceName()));
  }

  private static class LLMObsManualSpanFactory implements LLMObs.LLMObsSpanFactory {

    private final String serviceName;
    private final String defaultMLApp;

    public LLMObsManualSpanFactory(String defaultMLApp, String serviceName) {
      this.defaultMLApp = defaultMLApp;
      this.serviceName = serviceName;
    }

    @Override
    public LLMObsSpan startLLMSpan(
        String spanName,
        String modelName,
        String modelProvider,
        @Nullable String mlApp,
        @Nullable String sessionID) {

      DDLLMObsSpan span =
          new DDLLMObsSpan(
              Tags.LLMOBS_LLM_SPAN_KIND, spanName, getMLApp(mlApp), sessionID, serviceName);

      if (modelName == null || modelName.isEmpty()) {
        modelName = CUSTOM_MODEL_VAL;
      }
      span.setTag(LLMObsTags.MODEL_NAME, modelName);

      if (modelProvider == null || modelProvider.isEmpty()) {
        modelProvider = CUSTOM_MODEL_VAL;
      }
      span.setTag(LLMObsTags.MODEL_PROVIDER, modelProvider);
      return span;
    }

    @Override
    public LLMObsSpan startAgentSpan(
        String spanName, @Nullable String mlApp, @Nullable String sessionID) {
      return new DDLLMObsSpan(
          Tags.LLMOBS_AGENT_SPAN_KIND, spanName, getMLApp(mlApp), sessionID, serviceName);
    }

    @Override
    public LLMObsSpan startToolSpan(
        String spanName, @Nullable String mlApp, @Nullable String sessionID) {
      return new DDLLMObsSpan(
          Tags.LLMOBS_TOOL_SPAN_KIND, spanName, getMLApp(mlApp), sessionID, serviceName);
    }

    @Override
    public LLMObsSpan startTaskSpan(
        String spanName, @Nullable String mlApp, @Nullable String sessionID) {
      return new DDLLMObsSpan(
          Tags.LLMOBS_TASK_SPAN_KIND, spanName, getMLApp(mlApp), sessionID, serviceName);
    }

    @Override
    public LLMObsSpan startWorkflowSpan(
        String spanName, @Nullable String mlApp, @Nullable String sessionID) {
      return new DDLLMObsSpan(
          Tags.LLMOBS_WORKFLOW_SPAN_KIND, spanName, getMLApp(mlApp), sessionID, serviceName);
    }

    private String getMLApp(String mlApp) {
      if (mlApp == null || mlApp.isEmpty()) {
        return defaultMLApp;
      }
      return mlApp;
    }
  }
}
