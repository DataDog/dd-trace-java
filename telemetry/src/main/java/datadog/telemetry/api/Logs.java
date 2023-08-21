package datadog.telemetry.api;

import java.util.ArrayList;
import java.util.List;

public class Logs extends Payload {

  @com.squareup.moshi.Json(name = "logs")
  private List<LogMessage> messages = new ArrayList<>();

  /**
   * Get messages
   *
   * @return messages
   */
  public List<LogMessage> getMessages() {
    return messages;
  }

  /** Set messages */
  public void setMessages(List<LogMessage> messages) {
    this.messages = messages;
  }

  public Logs messages(List<LogMessage> messages) {
    this.messages = messages;
    return this;
  }

  /** Add one message */
  public Logs addMessage(LogMessage message) {
    this.messages.add(message);
    return this;
  }

  /** Create a string representation of this pojo. */
  @Override
  public String toString() {
    final StringBuffer sb = new StringBuffer("Logs{");
    sb.append("messages=").append(messages);
    sb.append('}');
    return sb.toString();
  }
}
