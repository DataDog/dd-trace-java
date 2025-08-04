package datadog.trace.llmobs;

import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.trace.api.Config;
import datadog.trace.api.llmobs.LLMObs;
import datadog.trace.api.llmobs.LLMObsSpan;
import datadog.trace.api.llmobs.LLMObsTags;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.llmobs.domain.DDLLMObsSpan;
import datadog.trace.llmobs.domain.LLMObsEval;
import datadog.trace.llmobs.domain.LLMObsInternal;
import java.lang.instrument.Instrumentation;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
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

    String mlApp = config.getLlmObsMlApp();
    LLMObsInternal.setLLMObsSpanFactory(
        new LLMObsManualSpanFactory(mlApp, config.getServiceName()));

    LLMObsInternal.setLLMObsEvalProcessor(new LLMObsCustomEvalProcessor(mlApp, sco, config));
  }

  private static class LLMObsCustomEvalProcessor implements LLMObs.LLMObsEvalProcessor {
    private final String defaultMLApp;
    private final EvalProcessingWorker evalProcessingWorker;

    public LLMObsCustomEvalProcessor(
        String defaultMLApp, SharedCommunicationObjects sco, Config config) {

      this.defaultMLApp = defaultMLApp;
      this.evalProcessingWorker =
          new EvalProcessingWorker(1024, 100, TimeUnit.MILLISECONDS, sco, config);
      this.evalProcessingWorker.start();
    }

    @Override
    public void SubmitEvaluation(
        LLMObsSpan llmObsSpan, String label, double scoreValue, Map<String, Object> tags) {
      SubmitEvaluation(llmObsSpan, label, scoreValue, defaultMLApp, tags);
    }

    @Override
    public void SubmitEvaluation(
        LLMObsSpan llmObsSpan,
        String label,
        double scoreValue,
        String mlApp,
        Map<String, Object> tags) {
      if (llmObsSpan == null) {
        LOGGER.error("null llm obs span provided, eval not recorded");
        return;
      }

      if (mlApp == null || mlApp.isEmpty()) {
        mlApp = defaultMLApp;
      }
      String traceID = llmObsSpan.getTraceId().toHexString();
      long spanID = llmObsSpan.getSpanId();
      LLMObsEval.Score score =
          new LLMObsEval.Score(
              traceID, spanID, System.currentTimeMillis(), mlApp, label, tags, scoreValue);
      if (!this.evalProcessingWorker.addToQueue(score)) {
        LOGGER.warn(
            "queue full, failed to add score eval, ml_app={}, trace_id={}, span_id={}, label={}",
            mlApp,
            traceID,
            spanID,
            label);
      }
    }

    @Override
    public void SubmitEvaluation(
        LLMObsSpan llmObsSpan, String label, String categoricalValue, Map<String, Object> tags) {
      SubmitEvaluation(llmObsSpan, label, categoricalValue, defaultMLApp, tags);
    }

    @Override
    public void SubmitEvaluation(
        LLMObsSpan llmObsSpan,
        String label,
        String categoricalValue,
        String mlApp,
        Map<String, Object> tags) {
      if (llmObsSpan == null) {
        LOGGER.error("null llm obs span provided, eval not recorded");
        return;
      }

      if (mlApp == null || mlApp.isEmpty()) {
        mlApp = defaultMLApp;
      }
      String traceID = llmObsSpan.getTraceId().toHexString();
      long spanID = llmObsSpan.getSpanId();
      LLMObsEval.Categorical category =
          new LLMObsEval.Categorical(
              traceID, spanID, System.currentTimeMillis(), mlApp, label, tags, categoricalValue);
      if (!this.evalProcessingWorker.addToQueue(category)) {
        LOGGER.warn(
            "queue full, failed to add categorical eval, ml_app={}, trace_id={}, span_id={}, label={}",
            mlApp,
            traceID,
            spanID,
            label);
      }
    }
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
        @Nullable String sessionId) {

      DDLLMObsSpan span =
          new DDLLMObsSpan(
              Tags.LLMOBS_LLM_SPAN_KIND, spanName, getMLApp(mlApp), sessionId, serviceName);

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
        String spanName, @Nullable String mlApp, @Nullable String sessionId) {
      return new DDLLMObsSpan(
          Tags.LLMOBS_AGENT_SPAN_KIND, spanName, getMLApp(mlApp), sessionId, serviceName);
    }

    @Override
    public LLMObsSpan startToolSpan(
        String spanName, @Nullable String mlApp, @Nullable String sessionId) {
      return new DDLLMObsSpan(
          Tags.LLMOBS_TOOL_SPAN_KIND, spanName, getMLApp(mlApp), sessionId, serviceName);
    }

    @Override
    public LLMObsSpan startTaskSpan(
        String spanName, @Nullable String mlApp, @Nullable String sessionId) {
      return new DDLLMObsSpan(
          Tags.LLMOBS_TASK_SPAN_KIND, spanName, getMLApp(mlApp), sessionId, serviceName);
    }

    @Override
    public LLMObsSpan startWorkflowSpan(
        String spanName, @Nullable String mlApp, @Nullable String sessionId) {
      return new DDLLMObsSpan(
          Tags.LLMOBS_WORKFLOW_SPAN_KIND, spanName, getMLApp(mlApp), sessionId, serviceName);
    }

    @Override
    public LLMObsSpan startEmbeddingSpan(
        String spanName,
        @Nullable String mlApp,
        @Nullable String modelProvider,
        @Nullable String modelName,
        @Nullable String sessionId) {
      if (modelProvider == null) {
        modelProvider = "custom";
      }
      DDLLMObsSpan embeddingSpan =
          new DDLLMObsSpan(
              Tags.LLMOBS_EMBEDDING_SPAN_KIND, spanName, getMLApp(mlApp), sessionId, serviceName);
      embeddingSpan.setTag(LLMObsTags.MODEL_PROVIDER, modelProvider);
      embeddingSpan.setTag(LLMObsTags.MODEL_NAME, modelName);
      return embeddingSpan;
    }

    public LLMObsSpan startRetrievalSpan(
        String spanName, @Nullable String mlApp, @Nullable String sessionId) {
      return new DDLLMObsSpan(
          Tags.LLMOBS_RETRIEVAL_SPAN_KIND, spanName, getMLApp(mlApp), sessionId, serviceName);
    }

    private String getMLApp(String mlApp) {
      if (mlApp == null || mlApp.isEmpty()) {
        return defaultMLApp;
      }
      return mlApp;
    }
  }
}
