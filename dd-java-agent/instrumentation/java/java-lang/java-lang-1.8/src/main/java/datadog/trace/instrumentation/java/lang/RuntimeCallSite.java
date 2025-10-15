package datadog.trace.instrumentation.java.lang;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastCallSites;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Sink;
import datadog.trace.api.iast.VulnerabilityTypes;
import datadog.trace.api.iast.sink.CommandInjectionModule;
import java.io.File;
import javax.annotation.Nullable;

@Sink(VulnerabilityTypes.COMMAND_INJECTION)
@CallSite(spi = IastCallSites.class)
public class RuntimeCallSite {

  @CallSite.Before("java.lang.Process java.lang.Runtime.exec(java.lang.String)")
  public static void beforeStart(@CallSite.Argument @Nullable final String command) {
    if (command != null) { // runtime fails if null
      final CommandInjectionModule module = InstrumentationBridge.COMMAND_INJECTION;
      if (module != null) {
        try {
          module.onRuntimeExec(command);
        } catch (final Throwable e) {
          module.onUnexpectedException("beforeExec threw", e);
        }
      }
    }
  }

  @CallSite.Before("java.lang.Process java.lang.Runtime.exec(java.lang.String[])")
  public static void beforeExec(@CallSite.Argument @Nullable final String[] cmdArray) {
    if (cmdArray != null && cmdArray.length > 0) { // runtime fails if null or empty
      final CommandInjectionModule module = InstrumentationBridge.COMMAND_INJECTION;
      if (module != null) {
        try {
          module.onRuntimeExec(cmdArray);
        } catch (final Throwable e) {
          module.onUnexpectedException("beforeExec threw", e);
        }
      }
    }
  }

  @CallSite.Before("java.lang.Process java.lang.Runtime.exec(java.lang.String, java.lang.String[])")
  public static void beforeExec(
      @CallSite.Argument @Nullable final String command,
      @CallSite.Argument @Nullable final String[] envp) {
    if (command != null) { // runtime fails if null
      final CommandInjectionModule module = InstrumentationBridge.COMMAND_INJECTION;
      if (module != null) {
        try {
          module.onRuntimeExec(envp, command);
        } catch (final Throwable e) {
          module.onUnexpectedException("beforeExec threw", e);
        }
      }
    }
  }

  @CallSite.Before(
      "java.lang.Process java.lang.Runtime.exec(java.lang.String[], java.lang.String[])")
  public static void beforeExec(
      @CallSite.Argument @Nullable final String[] cmdArray,
      @CallSite.Argument @Nullable final String[] envp) {
    if (cmdArray != null && cmdArray.length > 0) { // runtime fails if null or empty
      final CommandInjectionModule module = InstrumentationBridge.COMMAND_INJECTION;
      if (module != null) {
        try {
          module.onRuntimeExec(envp, cmdArray);
        } catch (final Throwable e) {
          module.onUnexpectedException("beforeExec threw", e);
        }
      }
    }
  }

  @CallSite.Before(
      "java.lang.Process java.lang.Runtime.exec(java.lang.String, java.lang.String[], java.io.File)")
  public static void beforeExec(
      @CallSite.Argument @Nullable final String command,
      @CallSite.Argument @Nullable final String[] envp,
      @CallSite.Argument @Nullable final File dir) {
    if (command != null) { // runtime fails if null
      final CommandInjectionModule module = InstrumentationBridge.COMMAND_INJECTION;
      if (module != null) {
        try {
          module.onRuntimeExec(envp, command);
        } catch (final Throwable e) {
          module.onUnexpectedException("beforeExec threw", e);
        }
      }
    }
  }

  @CallSite.Before(
      "java.lang.Process java.lang.Runtime.exec(java.lang.String[], java.lang.String[], java.io.File)")
  public static void beforeExec(
      @CallSite.Argument @Nullable final String[] cmdArray,
      @CallSite.Argument @Nullable final String[] envp,
      @CallSite.Argument @Nullable final File dir) {
    if (cmdArray != null && cmdArray.length > 0) { // runtime fails if null or empty
      final CommandInjectionModule module = InstrumentationBridge.COMMAND_INJECTION;
      if (module != null) {
        try {
          module.onRuntimeExec(envp, cmdArray);
        } catch (final Throwable e) {
          module.onUnexpectedException("beforeExec threw", e);
        }
      }
    }
  }
}
