package datadog.trace.instrumentation.servlet6;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.sink.UnvalidatedRedirectModule;
import datadog.trace.junit.utils.config.WithConfig;
import foo.bar.smoketest.DummyResponse;
import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Integration-level tests for Servlet 6.0-specific IAST instrumentation.
 *
 * <p>These tests extend {@link AbstractInstrumentationTest} so that ByteBuddy advice is actually
 * woven into the classes before any test method runs. Calling {@link
 * DummyResponse#sendRedirect(String, int, boolean)} here therefore exercises the real {@code
 * SendRedirect3ArgAdvice} — not a hand-rolled simulation.
 */
@WithConfig(key = "iast.enabled", value = "true")
public class Servlet60IastInstrumentationTest extends AbstractInstrumentationTest {

  @AfterEach
  void cleanup() {
    InstrumentationBridge.clearIastModules();
  }

  /**
   * Verifies that the {@code SendRedirect3ArgAdvice} (new in Servlet 6.1) fires the
   * unvalidated-redirect IAST sink when the location is non-empty.
   *
   * <p>This proves that ByteBuddy advice is woven on real {@link
   * DummyResponse#sendRedirect(String, int, boolean)} calls, not just the 1-arg variant.
   */
  @Test
  void sendRedirect3Arg_firesIastSinkForNonEmptyLocation() throws IOException {
    UnvalidatedRedirectModule module = mock(UnvalidatedRedirectModule.class);
    InstrumentationBridge.registerIastModule(module);

    DummyResponse response = new DummyResponse();
    response.sendRedirect("https://example.com/redirect", 302, false);

    verify(module).onRedirect("https://example.com/redirect");
  }

  /**
   * Verifies that the {@code SendRedirect3ArgAdvice} does NOT fire the IAST sink when location is
   * {@code null}.
   */
  @Test
  void sendRedirect3Arg_doesNotFireIastSinkForNullLocation() throws IOException {
    UnvalidatedRedirectModule module = mock(UnvalidatedRedirectModule.class);
    InstrumentationBridge.registerIastModule(module);

    DummyResponse response = new DummyResponse();
    response.sendRedirect(null, 302, false);

    verify(module, never()).onRedirect(null);
  }

  /**
   * Verifies that the {@code SendRedirect3ArgAdvice} does NOT fire the IAST sink for an empty
   * location string.
   */
  @Test
  void sendRedirect3Arg_doesNotFireIastSinkForEmptyLocation() throws IOException {
    UnvalidatedRedirectModule module = mock(UnvalidatedRedirectModule.class);
    InstrumentationBridge.registerIastModule(module);

    DummyResponse response = new DummyResponse();
    response.sendRedirect("", 302, false);

    verify(module, never()).onRedirect("");
  }

  /**
   * Sanity check: verifies that the ByteBuddy transformer installed by {@link
   * AbstractInstrumentationTest} is non-null — confirming advice weaving infrastructure is active.
   */
  @Test
  void instrumentationTransformerIsInstalled() {
    assertNotNull(
        INSTRUMENTATION, "ByteBuddy Instrumentation must be available for advice weaving");
  }
}
