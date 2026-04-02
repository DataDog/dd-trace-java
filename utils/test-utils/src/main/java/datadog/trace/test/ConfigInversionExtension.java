package datadog.trace.test;

import datadog.trace.config.inversion.ConfigHelper;
import datadog.trace.config.inversion.ConfigHelper.StrictnessPolicy;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * JUnit 5 extension that enforces {@link StrictnessPolicy#STRICT_TEST} mode on {@link ConfigHelper}
 * during test execution. Any access to an unsupported configuration key will throw {@link
 * IllegalArgumentException}.
 *
 * <p>Registered via {@code META-INF/services/org.junit.jupiter.api.extension.Extension} for
 * automatic discovery. Requires {@code junit.jupiter.extensions.autodetection.enabled=true}.
 */
public class ConfigInversionExtension implements BeforeAllCallback, AfterAllCallback {

  private static final ExtensionContext.Namespace NS =
      ExtensionContext.Namespace.create("dd", "config-inversion");

  private static final String PREVIOUS_POLICY = "previousPolicy";

  @Override
  public void beforeAll(ExtensionContext ctx) {
    StrictnessPolicy previous = ConfigHelper.get().configInversionStrictFlag();
    ctx.getStore(NS).put(PREVIOUS_POLICY, previous);
    ConfigHelper.get().setConfigInversionStrict(StrictnessPolicy.STRICT_TEST);
  }

  @Override
  public void afterAll(ExtensionContext ctx) {
    StrictnessPolicy previous = ctx.getStore(NS).get(PREVIOUS_POLICY, StrictnessPolicy.class);
    if (previous != null) {
      ConfigHelper.get().setConfigInversionStrict(previous);
    } else {
      ConfigHelper.get().setConfigInversionStrict(StrictnessPolicy.WARNING);
    }
  }
}
