package com.datadog.profiling.controller.jfr;

import datadog.trace.api.Platform;
import java.lang.instrument.Instrumentation;
import jdk.jfr.internal.JVM;

public class SimpleJFRAccess extends JFRAccess {
  public static class FactoryImpl implements JFRAccess.Factory {
    @Override
    public JFRAccess create(Instrumentation inst) {
      return !Platform.isJ9() && Platform.isJavaVersion(8) ? new SimpleJFRAccess() : null;
    }
  }

  @Override
  public boolean setStackDepth(int depth) {
    JVM.getJVM().setStackDepth(depth);
    return true;
  }
}
