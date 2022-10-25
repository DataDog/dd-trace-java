package datadog.smoketest;

public class TestCallee {
  public static String staticCall(String arg) {
    return arg.toUpperCase();
  }
}
