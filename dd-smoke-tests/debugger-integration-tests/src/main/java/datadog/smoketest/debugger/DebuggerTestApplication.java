package datadog.smoketest.debugger;

import static datadog.smoketest.debugger.TestApplicationHelper.waitForInstrumentation;
import static datadog.smoketest.debugger.TestApplicationHelper.waitForTransformerInstalled;
import static datadog.smoketest.debugger.TestApplicationHelper.waitForUpload;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

public class DebuggerTestApplication {
  private static final String LOG_FILENAME = System.getenv().get("DD_LOG_FILE");

  public static void main(String[] args) throws Exception {
    waitForTransformerInstalled(LOG_FILENAME);
    System.out.println(DebuggerTestApplication.class.getName());
    Main.run(args);
  }
}

class Main {
  private static final Map<String, Consumer<String>> methodsByName = new HashMap<>();
  private static final String LOG_FILENAME = System.getenv().get("DD_LOG_FILE");

  static void run(String[] args) throws Exception {
    registerMethods();
    String methodName = args[0];
    Consumer<String> method = methodsByName.get(methodName);
    if (method == null) {
      throw new RuntimeException("cannot find method: " + methodName);
    }
    int expectedUploads = Integer.parseInt(args[1]);
    System.out.println("Waiting for instrumentation...");
    waitForInstrumentation(LOG_FILENAME, Main.class.getName());
    System.out.println("Executing method: " + methodName);
    try {
      method.accept(args.length > 2 ? args[2] : null);
      System.out.println("Executed");
    } finally {
      waitForUpload(LOG_FILENAME, expectedUploads);
      System.out.println("Exiting...");
    }
  }

  private static void registerMethods() {
    methodsByName.put("emptyMethod", Main::emptyMethod);
    methodsByName.put("managementMethod", Main::managementMethod);
    methodsByName.put("fullMethod", Main::runFullMethod);
    methodsByName.put("multiProbesFullMethod", Main::runFullMethod);
    methodsByName.put("loopingFullMethod", Main::runLoopingFullMethod);
    methodsByName.put("exceptionMethod", Main::runExceptionMethod);
  }

  private static void emptyMethod(String arg) {}

  private static void managementMethod(String arg) {
    OperatingSystemMXBean operatingSystemMXBean = ManagementFactory.getOperatingSystemMXBean();
    System.out.println(operatingSystemMXBean.getAvailableProcessors());
  }

  private static void runFullMethod(String arg) {
    Map<String, String> map = new HashMap<>();
    map.put("key1", "val1");
    map.put("key2", "val2");
    map.put("key3", "val3");
    fullMethod(42, "foobar", 3.42, map, "var1", "var2", "var3");
  }

  private static void runLoopingFullMethod(String arg) {
    Map<String, String> map = new HashMap<>();
    map.put("key1", "val1");
    map.put("key2", "val2");
    map.put("key3", "val3");
    for (int i = 0; i < Integer.parseInt(arg); i++) {
      fullMethod(42, "foobar", 3.42, map, "var1", "var2", "var3");
    }
  }

  private static void runExceptionMethod(String s) {
    exceptionMethod(s);
  }

  private static String fullMethod(
      int argInt, String argStr, double argDouble, Map<String, String> argMap, String... argVar) {
    try {
      System.out.println("fullMethod");
      return argInt
          + ", "
          + argStr
          + ", "
          + argDouble
          + ", "
          + argMap
          + ", "
          + String.join(",", argVar);
    } catch (Exception ex) {
      ex.printStackTrace();
      return null;
    }
  }

  private static void exceptionMethod(String arg) {
    if (arg.equals("uncaught")) {
      throw new RuntimeException("oops uncaught!");
    }
    if (arg.equals("caught")) {
      try {
        throw new IllegalArgumentException("oops caught!");
      } catch (IllegalArgumentException ex) {
        ex.printStackTrace();
      }
    }
  }
}
