package datadog.trace.junit.utils.context;

import de.thetaphi.forbiddenapis.SuppressForbidden;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * JUnit 5 extension that allows re-registration of context managers so each test can use a fresh
 * tracer. This is needed because {@code ContextManager} and {@code ContextBinder} are singletons
 * that normally reject re-registration.
 *
 * <p>Auto-registered when using {@link AllowContextTesting}. Can also be used explicitly via
 * {@code @ExtendWith(AllowContextTestingExtension.class)}.
 */
@SuppressForbidden
public class AllowContextTestingExtension implements BeforeAllCallback {

  @Override
  public void beforeAll(ExtensionContext context) {
    try {
      Class.forName("datadog.context.ContextManager").getMethod("allowTesting").invoke(null);
      Class.forName("datadog.context.ContextBinder").getMethod("allowTesting").invoke(null);
    } catch (Throwable ignore) {
      // don't block testing if context types aren't available
    }
  }
}
