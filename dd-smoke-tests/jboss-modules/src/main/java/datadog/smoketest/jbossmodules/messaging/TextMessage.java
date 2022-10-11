package datadog.smoketest.jbossmodules.messaging;

public class TextMessage implements Message {
  private final String text;

  public TextMessage(final String text) {
    this.text = text;
  }

  @Override
  public String toString() {
    return "\"" + text + "\"";
  }
}
