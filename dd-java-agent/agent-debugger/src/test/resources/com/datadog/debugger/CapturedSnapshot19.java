package com.datadog.debugger;

public class CapturedSnapshot19 {
  public static int main(String arg) {
    API api = new API(42);
    return api.process();
  }

}

class API {
  private final int value;
  public API(int value) {
    this.value = value;
  }

  public int process() {
    return value;
  }

  public int alternateProcess() {
    return value + 1;
  }
}
