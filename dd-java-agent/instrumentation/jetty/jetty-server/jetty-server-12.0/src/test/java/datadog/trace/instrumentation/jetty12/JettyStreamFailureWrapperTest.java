package datadog.trace.instrumentation.jetty12;

import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_CONTEXT_ATTRIBUTE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import datadog.appsec.api.blocking.BlockingException;
import datadog.context.Context;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.jetty.server.HttpStream;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link JettyStreamFailureWrapper}.
 *
 * <p>Uses dynamic proxy fakes so the tests compile against every Jetty 12 version.
 */
class JettyStreamFailureWrapperTest {

  private final List<String> streamCalls = new ArrayList<>();
  private final List<String> removedAttributes = new ArrayList<>();

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
    Context context = proxy(Context.class, (name, args) -> null);
    HttpStream stream = wrapper(context);

    stream.failed(new BlockingException("blocked"));

    assertEquals(List.of(DD_CONTEXT_ATTRIBUTE), removedAttributes);
    assertEquals(List.of("failed"), streamCalls);
  }

  @Test
  void succeededOnlyDelegates() {
    HttpStream stream = wrapper(proxy(Context.class, (name, args) -> null));

    stream.succeeded();

    assertEquals(List.of(), removedAttributes, "ResetAdvice finishes the span on success");
    assertEquals(List.of("succeeded"), streamCalls);
  }

  @Test
  void installerWrapsTheStream() {
    HttpStream wrapped = stream();
    HttpStream result =
        new JettyStreamFailureWrapper.Installer(request(null), proxy(Response.class, null))
            .apply(wrapped);

    assertSame(wrapped, ((HttpStream.Wrapper) result).getWrapped());
  }

  private HttpStream wrapper(Context context) {
    return new JettyStreamFailureWrapper(
        stream(), request(context), proxy(Response.class, (name, args) -> null));
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

  private interface Handler {
    Object handle(String methodName, Object[] args);
  }

  @SuppressWarnings("unchecked")
  private static <T> T proxy(Class<T> type, Handler handler) {
    return (T)
        Proxy.newProxyInstance(
            JettyStreamFailureWrapperTest.class.getClassLoader(),
            new Class<?>[] {type},
            (proxy, method, args) -> {
              switch (method.getName()) {
                case "toString":
                  return type.getSimpleName() + "Fake";
                case "hashCode":
                  return System.identityHashCode(proxy);
                case "equals":
                  return proxy == args[0];
                default:
                  return handler == null ? null : handler.handle(method.getName(), args);
              }
            });
  }
}
