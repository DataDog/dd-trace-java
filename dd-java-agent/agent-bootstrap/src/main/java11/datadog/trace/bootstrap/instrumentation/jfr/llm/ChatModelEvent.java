package datadog.trace.bootstrap.instrumentation.jfr.llm;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

@Name("datadog.ChatModel")
@Label("Chat Model")
@Description("LLM chat model invocation")
@Category({"Datadog", "LLM"})
@StackTrace(false)
@LLMOperation
public class ChatModelEvent extends Event {

  @Label("Model Id")
  private final String modelId;

  public ChatModelEvent(String modelId) {
    this.modelId = modelId;
    begin();
  }
}
