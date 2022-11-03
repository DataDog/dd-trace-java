package datadog.trace.instrumentation.java.lang;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastAdvice;
import java.io.File;
import javax.annotation.Nullable;

// TODO deal with the environment
@CallSite(spi = IastAdvice.class)
public class RuntimeCallSite {

  @CallSite.Before("java.lang.Process java.lang.Runtime.exec(java.lang.String)")
  public static void beforeStart(@CallSite.Argument @Nullable final String command) {
    RuntimeHelperContainer.onRuntimeExec(command);
  }

  @CallSite.Before("java.lang.Process java.lang.Runtime.exec(java.lang.String[])")
  public static void beforeExec(@CallSite.Argument @Nullable final String[] cmdArray) {
    RuntimeHelperContainer.onRuntimeExec(cmdArray);
  }

  @CallSite.Before("java.lang.Process java.lang.Runtime.exec(java.lang.String, java.lang.String[])")
  public static void beforeExec(
      @CallSite.Argument @Nullable final String command,
      @CallSite.Argument @Nullable final String[] envp) {
    RuntimeHelperContainer.onRuntimeExec(command);
  }

  @CallSite.Before(
      "java.lang.Process java.lang.Runtime.exec(java.lang.String[], java.lang.String[])")
  public static void beforeExec(
      @CallSite.Argument @Nullable final String[] cmdArray,
      @CallSite.Argument @Nullable final String[] envp) {
    RuntimeHelperContainer.onRuntimeExec(cmdArray);
  }

  @CallSite.Before(
      "java.lang.Process java.lang.Runtime.exec(java.lang.String, java.lang.String[], java.io.File)")
  public static void beforeExec(
      @CallSite.Argument @Nullable final String command,
      @CallSite.Argument @Nullable final String[] envp,
      @CallSite.Argument @Nullable final File dir) {
    RuntimeHelperContainer.onRuntimeExec(command);
  }

  @CallSite.Before(
      "java.lang.Process java.lang.Runtime.exec(java.lang.String[], java.lang.String[], java.io.File)")
  public static void beforeExec(
      @CallSite.Argument @Nullable final String[] cmdArray,
      @CallSite.Argument @Nullable final String[] envp,
      @CallSite.Argument @Nullable final File dir) {
    RuntimeHelperContainer.onRuntimeExec(cmdArray);
  }
}
