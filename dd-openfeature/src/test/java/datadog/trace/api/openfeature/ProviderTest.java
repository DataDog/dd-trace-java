package datadog.trace.api.openfeature;

import static java.lang.invoke.MethodHandles.publicLookup;
import static java.lang.invoke.MethodType.methodType;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.openfeature.sdk.Hook;
import java.lang.invoke.MethodHandle;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Checks the deprecated provider alias stays source and binary compatible. */
@SuppressWarnings("deprecation")
class ProviderTest {
  @Test
  void aliasIsTheDatadogProvider() {
    final Provider provider = new Provider();

    assertInstanceOf(com.datadog.openfeature.Provider.class, provider);
    assertEquals("datadog-openfeature-provider", provider.getMetadata().getName());
  }

  @Test
  void aliasOptOutSetterKeepsItsBinarySignature() throws Throwable {
    final Provider.Options options = new Provider.Options();
    final MethodHandle setter =
        publicLookup()
            .findVirtual(
                Provider.Options.class,
                "enableEvaluationTelemetryOptOutParameter",
                methodType(Provider.Options.class, boolean.class));

    assertSame(options, (Provider.Options) setter.invokeExact(options, true));
  }

  @Test
  void aliasOptionsKeepTheirType() {
    final Provider.Options options = new Provider.Options();

    final Provider.Options configured =
        options
            .initTimeout(5, SECONDS)
            .enableEvaluationTelemetryOptOutParameter(true)
            .evaluationTelemetryEnabled(false)
            .exposuresEnabled(false)
            .experimentationOptOutMode(com.datadog.openfeature.ExperimentationOptOutMode.IMMEDIATE);

    assertSame(options, configured);
    assertEquals(5, configured.getTimeout());
    assertEquals(SECONDS, configured.getUnit());
    assertInstanceOf(com.datadog.openfeature.Provider.class, new Provider(configured));
  }

  @Test
  void aliasOptionsTurnOffEvaluationTelemetryAndExposures() {
    final Provider provider =
        new Provider(
            new Provider.Options().evaluationTelemetryEnabled(false).exposuresEnabled(false));

    // The hook classes are package-private in com.datadog.openfeature, and the provider's hooks
    // run behind one composite hook, whose toString names them.
    final Set<String> hooks = new HashSet<>();
    for (final Hook<?> hook : provider.getProviderHooks()) {
      hooks.add(hook.getClass().getSimpleName());
      hooks.add(hook.toString());
    }
    final String names = hooks.toString();
    assertFalse(names.contains("FlagEvalLoggingHook"), names);
    assertFalse(names.contains("FlagEvalMetricsHook"), names);
    assertFalse(names.contains("ExposureLoggingHook"), names);
  }
}
