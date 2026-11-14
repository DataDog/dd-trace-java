package datadog.trace.instrumentation.jetty12;

import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_CONTEXT_ATTRIBUTE;
import static datadog.trace.instrumentation.jetty12.JettyFakes.fake;
import static datadog.trace.instrumentation.jetty12.JettyFakes.proxy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import datadog.appsec.api.blocking.BlockingException;
import datadog.context.Context;
import datadog.trace.bootstrap.ActiveSubsystems;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.eclipse.jetty.server.HttpStream;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

/** Unit tests for {@link JettyStreamFailureWrapper}. */
class JettyStreamFailureWrapperTest {

  private final List<String> streamCalls = new ArrayList<>();
  private final List<String> removedAttributes = new ArrayList<>();
  private boolean previousAppSecActive;

  @BeforeEach
  void saveAppSecActive() {
    previousAppSecActive = ActiveSubsystems.APPSEC_ACTIVE;
  }

  @AfterEach
  void restoreAppSecActive() {
    ActiveSubsystems.APPSEC_ACTIVE = previousAppSecActive;
  }

  @Test
  void findsDirectBlockingException() {
    BlockingException blocking = new BlockingException("blocked");

    assertSame(blocking, JettyStreamFailureWrapper.findBlockingException(blocking));
  }

  @Test
  void findsWrappedBlockingException() {
    BlockingException blocking = new BlockingException("blocked");

    assertSame(
        blocking,
        JettyStreamFailureWrapper.findBlockingException(
            new RuntimeException(new IllegalStateException(blocking))));
  }

  @Test
  void findsSuppressedBlockingException() {
    BlockingException blocking = new BlockingException("blocked");
    IOException combined = new IOException("write failed");
    combined.addSuppressed(blocking);

    assertSame(blocking, JettyStreamFailureWrapper.findBlockingException(combined));
  }

  @Test
  void ignoresOtherFailures() {
    assertNull(JettyStreamFailureWrapper.findBlockingException(null));
    assertNull(JettyStreamFailureWrapper.findBlockingException(new IOException("reset")));
  }

  @Test
  void delegatesFailureWithoutContext() {
    IOException failure = new IOException("reset");
    HttpStream stream = wrapper(null);

    stream.failed(failure);

    assertEquals(List.of(DD_CONTEXT_ATTRIBUTE), removedAttributes);
    assertEquals(List.of("failed"), streamCalls);
  }

  @Test
  void removesContextAndDelegatesFailureWhenContextHasNoSpan() {
    HttpStream stream = wrapper(fake(Context.class));

    stream.failed(new BlockingException("blocked"));

    assertEquals(List.of(DD_CONTEXT_ATTRIBUTE), removedAttributes);
    assertEquals(List.of("failed"), streamCalls);
  }

  @Test
  void succeededOnlyDelegates() {
    HttpStream stream = wrapper(fake(Context.class));

    stream.succeeded();

    assertEquals(List.of(), removedAttributes, "ResetAdvice finishes the span on success");
    assertEquals(List.of("succeeded"), streamCalls);
  }

  @TableTest({
    "Scenario        | AppSec Active | Installed",
    "AppSec active   | true          | 1        ",
    "AppSec inactive | false         | 0        "
  })
  @SuppressWarnings("unchecked")
  void installWrapsTheStreamOnlyWhenAppSecIsActive(boolean appSecActive, int expectedInstalled) {
    ActiveSubsystems.APPSEC_ACTIVE = appSecActive;
    List<Function<HttpStream, HttpStream>> installed = new ArrayList<>();
    Request request =
        proxy(
            Request.class,
            (name, args) -> {
              if ("addHttpStreamWrapper".equals(name)) {
                installed.add((Function<HttpStream, HttpStream>) args[0]);
              }
              return null;
            });

    JettyStreamFailureWrapper.install(request, fake(Response.class));

    assertEquals(expectedInstalled, installed.size());
    if (expectedInstalled > 0) {
      HttpStream wrapped = stream();
      HttpStream result = installed.get(0).apply(wrapped);
      assertSame(wrapped, ((HttpStream.Wrapper) result).getWrapped());
    }
  }

  private HttpStream wrapper(Context context) {
    return new JettyStreamFailureWrapper(stream(), request(context), fake(Response.class));
  }

  private HttpStream stream() {
    return proxy(
        HttpStream.class,
        (name, args) -> {
          streamCalls.add(name);
          return null;
        });
  }

  private Request request(Context context) {
    return proxy(
        Request.class,
        (name, args) -> {
          if ("removeAttribute".equals(name)) {
            removedAttributes.add((String) args[0]);
            return DD_CONTEXT_ATTRIBUTE.equals(args[0]) ? context : null;
          }
          return null;
        });
  }
}
