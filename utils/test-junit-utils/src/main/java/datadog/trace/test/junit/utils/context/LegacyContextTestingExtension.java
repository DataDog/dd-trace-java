package datadog.trace.test.junit.utils.context;

import de.thetaphi.forbiddenapis.SuppressForbidden;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/** JUnit 5 extension that decides whether to register the legacy approach to managing contexts. */
@SuppressForbidden
public class LegacyContextTestingExtension implements BeforeAllCallback {
  @Override
  public void beforeAll(ExtensionContext context) {
    try {
      Class.forName("datadog.trace.bootstrap.instrumentation.api.AgentTracer")
          .getMethod("maybeInstallLegacyContextManager")
          .invoke(null);
    } catch (Throwable ignore) {
      // don't block testing if the legacy approach to managing contexts isn't available
    }
  }
}
