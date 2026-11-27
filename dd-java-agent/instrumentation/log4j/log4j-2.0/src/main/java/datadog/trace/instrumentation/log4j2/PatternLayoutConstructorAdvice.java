package datadog.trace.instrumentation.log4j2;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.log.LogPatternInjector;
import net.bytebuddy.asm.Advice;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.apache.logging.log4j.core.pattern.MdcPatternConverter;

/** Rewrites the event pattern before Log4j compiles its converters. */
public class PatternLayoutConstructorAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void onEnter(@Advice.Argument(value = 2, readOnly = false) String pattern) {
    if (Config.get().isLogPatternInject()) {
      LogPatternInjector.Format format = LogPatternInjector.Format.LOG4J2;
      try {
        // Early 2.x treats a comma-separated option as a single MDC key.
        MdcPatternConverter.class.getDeclaredField("keys");
      } catch (NoSuchFieldException legacyVersion) {
        format = LogPatternInjector.Format.LOG4J2_LEGACY;
      }
      // EqualsReplacementConverter was introduced in 2.5; older releases use regex replacement.
      boolean supportsEquals =
          PatternLayout.class
                  .getClassLoader()
                  .getResource(
                      "org/apache/logging/log4j/core/pattern/EqualsReplacementConverter.class")
              != null;
      pattern =
          LogPatternInjector.injectLog4j(
              pattern == null ? PatternLayout.DEFAULT_CONVERSION_PATTERN : pattern,
              format,
              supportsEquals);
    }
  }
}
