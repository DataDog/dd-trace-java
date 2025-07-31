package com.datadog.profiling.controller.jfr;

import datadog.environment.JavaVirtualMachine;
import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;

import jdk.jfr.internal.EventWriter;
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
      } else if (JavaVirtualMachine.isJavaVersionAtLeast(22)) {
        try {
          Class<?> vtClass = Class.forName("java.lang.VirtualThread");
          log.debug("VirtualThread class modifiable: {}", inst.isModifiableClass(vtClass));
        } catch (Throwable ignored) {}
      }
      return null;
    }
  }

  private final Field evenWriterPositionField;
  private final MethodHandle getPositionFieldHolder;
  private final long fakePositionField = -1;

  public SimpleJFRAccess() {
    Field posField = null;
    MethodHandle positionHolderMh = null;
    try {
      // first setup the defaults
      // this will definitely not throw any exception
      posField = SimpleJFRAccess.class.getDeclaredField("evenWriterPositionField");
      positionHolderMh = MethodHandles.lookup().unreflect(SimpleJFRAccess.class.getDeclaredMethod("getThis")).bindTo(this);
      // now try setting up access to JFR internals
      Class<?> eventWriterClass = EventWriter.class;
      posField = eventWriterClass.getDeclaredField("currentPosition");
      posField.setAccessible(true);
      positionHolderMh = MethodHandles.lookup().unreflect(JVM.class.getMethod("getEventWriter"));
    } catch (Exception ignored) {}
    evenWriterPositionField = posField;
    getPositionFieldHolder = positionHolderMh;
  }

  // for the event writer position wrapper
  private SimpleJFRAccess getThis() {
    return this;
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
  public long getThreadWriterPosition() {
    try {
      return (long)evenWriterPositionField.get(getPositionFieldHolder.invoke());
    } catch (Throwable ignored) {
      // never gonna happen
    }
    return -1; // just return the same invalid position
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
