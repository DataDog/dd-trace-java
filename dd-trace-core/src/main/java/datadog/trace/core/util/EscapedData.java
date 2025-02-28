package datadog.trace.core.util;

public class EscapedData {
  private String data;
  private int size;

  public EscapedData(String data, int size) {
    this.data = data;
    this.size = size;
  }

  public EscapedData() {
    this.data = "";
    this.size = 0;
  }

  public String getData() {
    return data;
  }

  public int getSize() {
    return size;
  }

  public void setData(String data) {
    this.data = data;
  }

  public void incrementSize() {
    size++;
  }

  public void addSize(int delta) {
    size += delta;
  }
}
