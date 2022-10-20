package datadog.telemetry.api;

import java.util.ArrayList;
import java.util.List;

public class ExceptionsThrown extends Payload {
   
  com.squareup.moshi.Json(name = "logs")
  private List<Log> exceptions = new ArrayList<Log>();

  /**
   * Get exceptions
   * 
   * @return exceptions
   */
  public List<Log> getExceptions() { 
    return exceptions;
  }

  /** Set exceptions */
  public void setExceptions(List<Log> exceptions) {
    this.exceptions = exceptions;
  }

  public ExceptionsThrown exceptions(List<Log> exceptions) { 
    this.exceptions = exceptions;
    return this;
  }

  public ExceptionsThrown addExceptionItem(Log exceptionItem) { 
    this.exceptions.add(exceptionItem);
    return this;
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class ExceptionsThrown {\n");
    sb.append("    ").append(super.toString()).append("\n");
    sb.append("    exceptions: ").append(exceptions).append("\n");
    sb.append("}");
    return sb.toString();
  }
}
