package datadog.trace.instrumentation.jetty12;

import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;

/**
 * Dynamic proxy fakes shared by the Jetty 12 unit tests, so they compile against every Jetty 12
 * version (the core interfaces gain abstract methods across minor versions).
 */
final class JettyFakes {

  /** Handles every proxied call except {@code toString}, {@code hashCode} and {@code equals}. */
  interface Handler {
    Object handle(String methodName, Object[] args);
  }

  private JettyFakes() {}

  @SuppressWarnings("unchecked")
  static <T> T proxy(Class<T> type, Handler handler) {
    return (T)
        Proxy.newProxyInstance(
            JettyFakes.class.getClassLoader(),
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
                  return handler.handle(method.getName(), args);
              }
            });
  }

  /** A fake whose methods all return {@code null}. */
  static <T> T fake(Class<T> type) {
    return proxy(type, (name, args) -> null);
  }

  static byte[] toBytes(ByteBuffer buffer) {
    byte[] bytes = new byte[buffer.remaining()];
    buffer.duplicate().get(bytes);
    return bytes;
  }
}
