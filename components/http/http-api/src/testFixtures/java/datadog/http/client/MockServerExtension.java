package datadog.http.client;

import static org.mockserver.integration.ClientAndServer.startClientAndServer;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.mockserver.client.MockServerClient;
import org.mockserver.integration.ClientAndServer;

/** Minimal JUnit lifecycle adapter for the shaded MockServer server artifact. */
public final class MockServerExtension
    implements ParameterResolver, BeforeAllCallback, AfterAllCallback {
  private ClientAndServer server;

  @Override
  public void beforeAll(ExtensionContext context) {
    this.server = startClientAndServer(0);
  }

  @Override
  public boolean supportsParameter(
      ParameterContext parameterContext, ExtensionContext extensionContext) {
    return MockServerClient.class.isAssignableFrom(parameterContext.getParameter().getType());
  }

  @Override
  public Object resolveParameter(
      ParameterContext parameterContext, ExtensionContext extensionContext) {
    if (this.server == null) {
      throw new ParameterResolutionException("MockServer has not been started");
    }
    return this.server;
  }

  @Override
  public void afterAll(ExtensionContext context) {
    if (this.server != null && this.server.isRunning()) {
      this.server.stop();
    }
  }
}
