package com.datadog.debugger;

public class CapturedSnapshot32 {
  public static int main(String arg) throws Exception {
    arg = processArg(arg);
    return 42;
  }

  public static String processArg(String arg) {
    if (arg.startsWith("-")) {
      arg = arg.substring(1);
    }
    return arg;
  }
}
