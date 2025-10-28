package datadog.trace.instrumentation.java.lang;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastCallSites;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Sink;
import datadog.trace.api.iast.VulnerabilityTypes;
import datadog.trace.api.iast.sink.XssModule;
import java.util.Locale;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

@Sink(VulnerabilityTypes.XSS)
@CallSite(spi = IastCallSites.class)
public class PrintWriterCallSite {

  @CallSite.Before("void java.io.PrintWriter.write(java.lang.String, int, int)")
  @CallSite.Before("void java.io.PrintWriter.write(java.lang.String)")
  @CallSite.Before("void java.io.PrintWriter.println(java.lang.String)")
  @CallSite.Before("void java.io.PrintWriter.print(java.lang.String)")
  public static void beforeStringParam(@CallSite.Argument(0) @Nonnull final String s) {
    final XssModule module = InstrumentationBridge.XSS;
    if (module != null) {
      try {
        module.onXss(s);
      } catch (final Throwable e) {
        module.onUnexpectedException("beforeStringParam threw", e);
      }
    }
  }

  @CallSite.Before("void java.io.PrintWriter.write(char[], int, int)")
  @CallSite.Before("void java.io.PrintWriter.write(char[])")
  @CallSite.Before("void java.io.PrintWriter.println(char[])")
  @CallSite.Before("void java.io.PrintWriter.print(char[])")
  public static void beforeCharArrayParam(@CallSite.Argument(0) @Nonnull final char[] buf) {
    final XssModule module = InstrumentationBridge.XSS;
    if (module != null) {
      try {
        module.onXss(buf);
      } catch (final Throwable e) {
        module.onUnexpectedException("beforeCharArrayParam threw", e);
      }
    }
  }

  @CallSite.Before(
      "java.io.PrintWriter java.io.PrintWriter.format(java.util.Locale, java.lang.String, java.lang.Object[])")
  @CallSite.Before(
      "java.io.PrintWriter java.io.PrintWriter.printf(java.util.Locale, java.lang.String, java.lang.Object[])")
  public static void beforeLocaleAndStringAndObjects(
      @CallSite.Argument(0) @Nonnull final Locale locale,
      @CallSite.Argument(1) @Nonnull final String format,
      @CallSite.Argument(2) @Nullable final Object[] args) {
    final XssModule module = InstrumentationBridge.XSS;
    if (module != null) {
      try {
        module.onXss(format, args);
      } catch (final Throwable e) {
        module.onUnexpectedException("beforeLocaleAndStringAndObjects threw", e);
      }
    }
  }

  @CallSite.Before(
      "java.io.PrintWriter java.io.PrintWriter.format(java.lang.String, java.lang.Object[])")
  @CallSite.Before(
      "java.io.PrintWriter java.io.PrintWriter.printf(java.lang.String, java.lang.Object[])")
  public static void beforeStringAndObjects(
      @CallSite.Argument(0) @Nonnull final String format,
      @CallSite.Argument(1) @Nullable final Object[] args) {
    final XssModule module = InstrumentationBridge.XSS;
    if (module != null) {
      try {
        module.onXss(format, args);
      } catch (final Throwable e) {
        module.onUnexpectedException("beforeStringAndObjects threw", e);
      }
    }
  }
}
