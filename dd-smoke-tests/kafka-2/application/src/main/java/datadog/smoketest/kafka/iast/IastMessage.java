package datadog.smoketest.kafka.iast;

public class IastMessage {

  private String value;

  public IastMessage() {}

  public IastMessage(final String value) {
    this.value = value;
  }

  public String getValue() {
    return value;
  }

  public void setValue(String value) {
    this.value = value;
  }
}
