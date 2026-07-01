package datadog.trace.test;

import datadog.trace.config.inversion.ConfigHelper;
import datadog.trace.config.inversion.ConfigHelper.StrictnessPolicy;
import java.util.List;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * JUnit 5 extension that enforces {@link StrictnessPolicy#STRICT_TEST} mode on {@link ConfigHelper}
 * during test execution. Any access to an unsupported configuration key will throw {@link
 * IllegalArgumentException} and be collected for assertion in {@link #afterAll}.
 *
 * <p>Registered via {@code META-INF/services/org.junit.jupiter.api.extension.Extension} for
 * automatic discovery. Requires {@code junit.jupiter.extensions.autodetection.enabled=true}.
 */
public class ConfigInversionExtension implements BeforeAllCallback, AfterAllCallback {

  private StrictnessPolicy previousPolicy;

  @Override
  public void beforeAll(ExtensionContext ctx) {
    previousPolicy = ConfigHelper.get().configInversionStrictFlag();
    ConfigHelper.get().setConfigInversionStrict(StrictnessPolicy.STRICT_TEST);
  }

  @Override
  public void afterAll(ExtensionContext ctx) {
    List<String> unsupported = ConfigHelper.get().drainUnsupportedConfigs();
    ConfigHelper.get().setConfigInversionStrict(previousPolicy);

    if (!unsupported.isEmpty()) {
      throw new AssertionError(
          "Unsupported configurations found during test. "
              + "Add these to metadata/supported-configurations.json or opt out with StrictnessPolicy.TEST:\n  "
              + String.join("\n  ", unsupported));
    }
  }
}
