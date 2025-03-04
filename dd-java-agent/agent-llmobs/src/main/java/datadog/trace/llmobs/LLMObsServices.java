package datadog.trace.llmobs;

import datadog.communication.BackendApi;
import datadog.communication.BackendApiFactory;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.trace.api.Config;
import datadog.trace.llmobs.domain.SpanContextInfo;
import java.util.Stack;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LLMObsServices {

  private static final Logger logger = LoggerFactory.getLogger(LLMObsServices.class);

  final Config config;
  final BackendApi backendApi;

  ThreadLocal<Stack<SpanContextInfo>> activeSpanContext = new ThreadLocal<>();

  LLMObsServices(Config config, SharedCommunicationObjects sco) {
    this.config = config;
    this.backendApi =
        new BackendApiFactory(config, sco).createBackendApi(BackendApiFactory.Intake.LLMOBS_API);
  }

  public SpanContextInfo getParentContext() {
    if (activeSpanContext.get() == null || activeSpanContext.get().isEmpty()) {
      return null;
    }
    return activeSpanContext.get().peek();
  }

  @Nonnull
  public SpanContextInfo getActiveSpanContext() {
    if (activeSpanContext.get() == null || activeSpanContext.get().isEmpty()) {
      return new SpanContextInfo();
    }
    return activeSpanContext.get().peek();
  }

  public void setActiveSpanContext(SpanContextInfo spanContext) {
    Stack<SpanContextInfo> contexts = activeSpanContext.get();
    if (contexts == null) {
      contexts = new Stack<>();
    }
    contexts.push(spanContext);
    this.activeSpanContext.set(contexts);
  }

  public void removeActiveSpanContext() {
    Stack<SpanContextInfo> contexts = activeSpanContext.get();
    if (contexts == null) {
      return;
    }
    contexts.pop();
  }
}
