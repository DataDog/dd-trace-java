package datadog.trace.bootstrap.instrumentation.jfr.llm;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

@Name("datadog.ToolExecutor")
@Label("Tool Executor")
@Description("LLM tool invocation")
@Category({"Datadog", "LLM"})
@StackTrace(false)
@LLMOperation
public class ToolExecutorEvent extends Event {

  @Label("Tool Name")
  private final String toolName;

  public ToolExecutorEvent(String toolName) {
    this.toolName = toolName;
    begin();
  }
}
