package datadog.trace.instrumentation.logback;

import static net.bytebuddy.matcher.ElementMatchers.isBootstrapClassLoader;
import static net.bytebuddy.matcher.ElementMatchers.none;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Config;
import net.bytebuddy.matcher.ElementMatcher;

/** Supplies existing MDC enrichment to opt-in layouts whose events are bootstrap-loaded. */
@AutoService(InstrumenterModule.class)
public class BootstrapPatternLoggingEventInstrumentation extends LoggingEventInstrumentation
    implements Instrumenter.ForKnownTypes, Instrumenter.ForBootstrap {
  @Override
  protected boolean defaultEnabled() {
    return Config.get().isLogPatternInject();
  }

  @Override
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
    return Config.get().isLogPatternInject() ? isBootstrapClassLoader() : none();
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "ch.qos.logback.classic.spi.LoggingEvent", "ch.qos.logback.classic.spi.LoggingEventVO"
    };
  }
}
