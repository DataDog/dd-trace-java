package datadog.smoketest.agentisolation;

import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.lang.instrument.Instrumentation;

@SuppressForbidden
public class Agent {

  public static void premain(String args, Instrumentation instrumentation) {
    // load the classes which trigger datadog instrumentation first
    for (String className : args.split(",")) {
      try {
        System.err.println("Loading" + Class.forName(className).getName());
      } catch (ClassNotFoundException e) {
        e.printStackTrace(System.err);
      }
    }
    for (Class<?> klass : instrumentation.getAllLoadedClasses()) {
      try {
        System.err.println(klass.getSimpleName());
      } catch (Throwable t) {
        System.err.println("___ERROR____:" + klass.getName());
      }
    }
  }
}
