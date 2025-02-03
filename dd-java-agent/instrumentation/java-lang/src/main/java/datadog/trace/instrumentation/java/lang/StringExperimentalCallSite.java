package datadog.trace.instrumentation.java.lang;

import static datadog.trace.api.telemetry.LogCollector.SEND_TELEMETRY;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastCallSites;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Propagation;
import datadog.trace.api.iast.propagation.StringModule;
import datadog.trace.api.iast.telemetry.IastMetric;
import datadog.trace.api.iast.telemetry.IastMetricCollector;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Propagation
@CallSite(
    spi = IastCallSites.class,
    enabled = {"datadog.trace.api.iast.IastEnabledChecks", "isExperimentalPropagationEnabled"})
public class StringExperimentalCallSite {

  private static final Logger LOGGER = LoggerFactory.getLogger(StringExperimentalCallSite.class);

  @CallSite.After(
      "java.lang.String java.lang.String.replace(java.lang.CharSequence, java.lang.CharSequence)")
  public static String afterReplaceCharSeq(
      @CallSite.This @Nonnull final String self,
      @CallSite.Argument(0) final CharSequence oldCharSeq,
      @CallSite.Argument(1) final CharSequence newCharSeq,
      @CallSite.Return @Nonnull final String result) {
    String newReplaced = "";
    final StringModule module = InstrumentationBridge.STRING;
    if (module != null) {
      try {
        newReplaced = module.onStringReplace(self, oldCharSeq, newCharSeq);
      } catch (final Throwable e) {
        module.onUnexpectedException("afterReplaceCharSeq threw", e);
      }
    }

    IastMetricCollector.add(IastMetric.EXPERIMENTAL_PROPAGATION, 1);

    if (!result.equals(newReplaced)) {
      LOGGER.debug(
          SEND_TELEMETRY,
          "afterReplaceCharSeq failed due to a different result between original replace and new replace, originalLength: {}, newLength: {}",
          result.length(),
          newReplaced != null ? newReplaced.length() : 0);

      return result;
    }

    return newReplaced;
  }

  @CallSite.After(
      "java.lang.String java.lang.String.replaceAll(java.lang.String, java.lang.String)")
  @SuppressForbidden
  public static String afterReplaceAll(
      @CallSite.This final String self,
      @CallSite.Argument(0) final String regex,
      @CallSite.Argument(1) final String replacement,
      @CallSite.Return @Nonnull final String result) {
    String newReplaced = "";
    final StringModule module = InstrumentationBridge.STRING;
    if (module != null) {
      try {
        newReplaced = module.onStringReplace(self, regex, replacement, Integer.MAX_VALUE);
      } catch (final Throwable e) {
        module.onUnexpectedException("afterReplaceAll threw", e);
      }
    }

    IastMetricCollector.add(IastMetric.EXPERIMENTAL_PROPAGATION, 1);

    if (!result.equals(newReplaced)) {
      LOGGER.debug(
          SEND_TELEMETRY,
          "afterReplaceAll failed due to a different result between original replace and new replace, originalLength: {}, newLength: {}",
          result.length(),
          newReplaced != null ? newReplaced.length() : 0);

      return result;
    }

    return newReplaced;
  }

  @CallSite.After(
      "java.lang.String java.lang.String.replaceFirst(java.lang.String, java.lang.String)")
  @SuppressForbidden
  public static String afterReplaceFirst(
      @CallSite.This final String self,
      @CallSite.Argument(0) final String regex,
      @CallSite.Argument(1) final String replacement,
      @CallSite.Return @Nonnull final String result) {
    String newReplaced = "";
    final StringModule module = InstrumentationBridge.STRING;
    if (module != null) {
      try {
        newReplaced = module.onStringReplace(self, regex, replacement, 1);
      } catch (final Throwable e) {
        module.onUnexpectedException("afterReplaceFirst threw", e);
      }
    }

    IastMetricCollector.add(IastMetric.EXPERIMENTAL_PROPAGATION, 1);

    if (!result.equals(newReplaced)) {
      LOGGER.debug(
          SEND_TELEMETRY,
          "afterReplaceFirst failed due to a different result between original replace and new replace, originalLength: {}, newLength: {}",
          result.length(),
          newReplaced != null ? newReplaced.length() : 0);

      return result;
    }

    return newReplaced;
  }
}
