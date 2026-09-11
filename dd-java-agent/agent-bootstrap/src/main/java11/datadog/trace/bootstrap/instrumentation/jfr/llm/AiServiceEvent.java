package datadog.trace.bootstrap.instrumentation.jfr.llm;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

@Name("datadog.AiService")
@Label("AI Service")
@Description("AI service method invocation")
@Category({"Datadog", "LLM"})
@StackTrace(false)
@LLMOperation
public class AiServiceEvent extends Event {

  @Label("Service Type")
  private final String serviceType;

  @Label("Method Name")
  private final String methodName;

  @Label("Trace ID")
  private String traceId;

  @Label("Span ID")
  private String spanId;

  public AiServiceEvent(String serviceType, String methodName) {
    this.serviceType = serviceType;
    this.methodName = methodName;
    begin();
  }

  public void setSpanContext(String traceId, String spanId) {
    this.traceId = traceId;
    this.spanId = spanId;
  }
}
