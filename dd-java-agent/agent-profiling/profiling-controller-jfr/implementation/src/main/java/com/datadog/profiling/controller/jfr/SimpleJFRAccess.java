package com.datadog.profiling.controller.jfr;

import datadog.trace.api.Platform;
import java.lang.instrument.Instrumentation;
import jdk.jfr.internal.JVM;

public class SimpleJFRAccess extends JFRAccess {
  public static class FactoryImpl implements JFRAccess.Factory {
    @Override
    public JFRAccess create(Instrumentation inst) {
      if (Platform.isJavaVersion(8)) {
        // if running on Java 8 return either SimpleJFRAccess or NOOP
        // J9 and Oracle JDK 8 do not contain the required classes and methods to set the stackdepth
        // programmatically
        return !Platform.isJ9() && !Platform.isOracleJDK8()
            ? new SimpleJFRAccess()
            : JFRAccess.NOOP;
      }
      return null;
    }
  }

  @Override
  public boolean setStackDepth(int depth) {
    JVM.getJVM().setStackDepth(depth);
    return true;
  }
}
