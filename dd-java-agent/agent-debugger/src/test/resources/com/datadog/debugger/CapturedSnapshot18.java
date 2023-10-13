package com.datadog.debugger;

public class CapturedSnapshot18 {
  public static int main(String arg) {
    processWithException();
    return 42;
  }

  private static void processWithException() {
    try {
      Integer.parseInt("a");
    } catch (Exception ex) {
      ex.fillInStackTrace();
      ex.printStackTrace();
    }
  }
}
