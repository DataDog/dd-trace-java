package datadog.trace.instrumentation.servlet2.callsite;

import java.util.HashSet;

public class MockTainter {
  static HashSet<Object> taintedObjects = new HashSet<>();

  public static void taintObject(Object o) {
    taintedObjects.add(o);
  }

  public static boolean isTainted(Object o) {
    return taintedObjects.contains(o);
  }
}
