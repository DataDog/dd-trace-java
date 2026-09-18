package datadog.trace.instrumentation.langchain4j;

import datadog.trace.bootstrap.instrumentation.jfr.llm.ChatModelEvent;
import datadog.trace.instrumentation.llm.tck.LlmObsHandleTck;
import jdk.jfr.Event;

class LlmCallHandleTckTest extends LlmObsHandleTck {

  @Override
  protected Event makeJfrEvent() {
    return new ChatModelEvent("test-model");
  }
}
