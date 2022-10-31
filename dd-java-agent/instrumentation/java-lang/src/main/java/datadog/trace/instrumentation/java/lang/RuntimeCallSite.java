package datadog.trace.instrumentation.java.lang;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastAdvice;
import datadog.trace.api.iast.InstrumentationBridge;
import java.io.File;
import javax.annotation.Nullable;

// TODO deal with the environment
@CallSite(spi = IastAdvice.class)
public class RuntimeCallSite {

  @CallSite.Before("java.lang.Process java.lang.Runtime.exec(java.lang.String)")
  public static void beforeStart(@CallSite.Argument @Nullable final String command) {
    InstrumentationBridge.onRuntimeExec(command);
  }

  @CallSite.Before("java.lang.Process java.lang.Runtime.exec(java.lang.String[])")
  public static void beforeExec(@CallSite.Argument @Nullable final String[] cmdArray) {
    InstrumentationBridge.onRuntimeExec(cmdArray);
  }

  @CallSite.Before("java.lang.Process java.lang.Runtime.exec(java.lang.String, java.lang.String[])")
  public static void beforeExec(
      @CallSite.Argument @Nullable final String command,
      @CallSite.Argument @Nullable final String[] envp) {
    InstrumentationBridge.onRuntimeExec(command);
  }

  @CallSite.Before(
      "java.lang.Process java.lang.Runtime.exec(java.lang.String[], java.lang.String[])")
  public static void beforeExec(
      @CallSite.Argument @Nullable final String[] cmdArray,
      @CallSite.Argument @Nullable final String[] envp) {
    InstrumentationBridge.onRuntimeExec(cmdArray);
  }

  @CallSite.Before(
      "java.lang.Process java.lang.Runtime.exec(java.lang.String, java.lang.String[], java.io.File)")
  public static void beforeExec(
      @CallSite.Argument @Nullable final String command,
      @CallSite.Argument @Nullable final String[] envp,
      @CallSite.Argument @Nullable final File dir) {
    InstrumentationBridge.onRuntimeExec(command);
  }

  @CallSite.Before(
      "java.lang.Process java.lang.Runtime.exec(java.lang.String[], java.lang.String[], java.io.File)")
  public static void beforeExec(
      @CallSite.Argument @Nullable final String[] cmdArray,
      @CallSite.Argument @Nullable final String[] envp,
      @CallSite.Argument @Nullable final File dir) {
    InstrumentationBridge.onRuntimeExec(cmdArray);
  }
}
