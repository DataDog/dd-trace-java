package datadog.trace.test;

import datadog.trace.config.inversion.ConfigHelper;
import datadog.trace.config.inversion.ConfigHelper.StrictnessPolicy;
import de.thetaphi.forbiddenapis.SuppressForbidden;
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
 *
 * <p>Gracefully no-ops when {@code config-utils} is not on the classpath (e.g. in modules that only
 * depend on {@code test-utils}).
 */
public class ConfigInversionExtension implements BeforeAllCallback, AfterAllCallback {

  private static final ExtensionContext.Namespace NS =
      ExtensionContext.Namespace.create("dd", "config-inversion");

  private static final String PREVIOUS_POLICY = "previousPolicy";
  private static final String AVAILABLE = "available";

  @SuppressForbidden
  private static boolean configHelperAvailable() {
    try {
      Class.forName("datadog.trace.config.inversion.ConfigHelper");
      return true;
    } catch (ClassNotFoundException | NoClassDefFoundError e) {
      return false;
    }
  }

  @Override
  public void beforeAll(ExtensionContext ctx) {
    boolean available = configHelperAvailable();
    ctx.getStore(NS).put(AVAILABLE, available);
    if (!available) {
      return;
    }
    StrictnessPolicy previous = ConfigHelper.get().configInversionStrictFlag();
    ctx.getStore(NS).put(PREVIOUS_POLICY, previous);
    ConfigHelper.get().setConfigInversionStrict(StrictnessPolicy.STRICT_TEST);
  }

  @Override
  public void afterAll(ExtensionContext ctx) {
    Boolean available = ctx.getStore(NS).get(AVAILABLE, Boolean.class);
    if (available == null || !available) {
      return;
    }

    List<String> unsupported = ConfigHelper.get().drainUnsupportedConfigs();

    StrictnessPolicy previous = ctx.getStore(NS).get(PREVIOUS_POLICY, StrictnessPolicy.class);
    if (previous != null) {
      ConfigHelper.get().setConfigInversionStrict(previous);
    } else {
      ConfigHelper.get().setConfigInversionStrict(StrictnessPolicy.WARNING);
    }

    if (!unsupported.isEmpty()) {
      throw new AssertionError(
          "Unsupported configurations found during test. "
              + "Add these to metadata/supported-configurations.json or opt out with StrictnessPolicy.TEST:\n  "
              + String.join("\n  ", unsupported));
    }
  }
}
