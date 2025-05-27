package com.datadog.profiling.controller.jfr;

import datadog.environment.JavaVirtualMachine;
import java.lang.instrument.Instrumentation;
import jdk.jfr.internal.JVM;
import jdk.jfr.internal.Repository;
import jdk.jfr.internal.SecuritySupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SimpleJFRAccess extends JFRAccess {
  private static final Logger log = LoggerFactory.getLogger(SimpleJFRAccess.class);

  public static class FactoryImpl implements JFRAccess.Factory {
    @Override
    public JFRAccess create(Instrumentation inst) {
      if (JavaVirtualMachine.isJavaVersion(8)) {
        // if running on Java 8 return either SimpleJFRAccess or NOOP
        // J9 and Oracle JDK 8 do not contain the required classes and methods to set the stackdepth
        // programmatically
        return !JavaVirtualMachine.isJ9() && !JavaVirtualMachine.isOracleJDK8()
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

  @Override
  public boolean setBaseLocation(String location) {
    try {
      Repository.getRepository().setBasePath(new SecuritySupport.SafePath(location));
    } catch (Exception e) {
      log.warn("Failed to set JFR base location: {}", e.getMessage());
      return false;
    }
    return true;
  }

  @Override
  public long timestamp() {
    return JVM.counterTime();
  }

  @Override
  public double toNanosConversionFactor() {
    return JVM.getJVM().getTimeConversionFactor();
  }
}
