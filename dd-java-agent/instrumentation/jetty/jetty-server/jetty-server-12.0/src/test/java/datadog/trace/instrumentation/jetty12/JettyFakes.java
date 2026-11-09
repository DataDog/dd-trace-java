package datadog.trace.instrumentation.jetty12;

import datadog.trace.api.internal.TraceSegment;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;

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

  /** A core {@link Request} fake recording attributes. */
  static final class FakeRequest {
    final String method;
    final HttpFields headers;
    final Map<String, Object> attributes = new HashMap<>();
    final Request proxy;
    RuntimeException headersFailure;

    FakeRequest(String method, String accept) {
      this.method = method;
      HttpFields.Mutable fields = HttpFields.build();
      if (accept != null) {
        fields.put(HttpHeader.ACCEPT, accept);
      }
      this.headers = fields.asImmutable();
      this.proxy =
          proxy(
              Request.class,
              (name, args) -> {
                switch (name) {
                  case "getMethod":
                    return this.method;
                  case "getHeaders":
                    if (headersFailure != null) {
                      throw headersFailure;
                    }
                    return headers;
                  case "setAttribute":
                    return attributes.put((String) args[0], args[1]);
                  case "getAttribute":
                    return attributes.get((String) args[0]);
                  default:
                    throw new UnsupportedOperationException(name);
                }
              });
    }
  }

  /** A core {@link Response} fake recording resets and writes; writes can be left pending. */
  static final class FakeResponse {
    final HttpFields.Mutable headers = HttpFields.build();
    final List<byte[]> writes = new ArrayList<>();
    final List<Boolean> lastFlags = new ArrayList<>();
    final Response proxy;
    int status;
    int resets;
    boolean committed;
    boolean completeWrites = true;
    Callback pending;
    RuntimeException resetFailure;
    RuntimeException writeThrows;
    Throwable writeFailure;

    FakeResponse() {
      this.proxy =
          proxy(
              Response.class,
              (name, args) -> {
                switch (name) {
                  case "isCommitted":
                    return committed;
                  case "getStatus":
                    return status;
                  case "setStatus":
                    status = (Integer) args[0];
                    return null;
                  case "getHeaders":
                    return headers;
                  case "reset":
                    if (resetFailure != null) {
                      throw resetFailure;
                    }
                    resets++;
                    status = 0;
                    headers.clear();
                    return null;
                  case "write":
                    if (writeThrows != null) {
                      throw writeThrows;
                    }
                    lastFlags.add((Boolean) args[0]);
                    writes.add(toBytes((ByteBuffer) args[1]));
                    Callback callback = (Callback) args[2];
                    if (!completeWrites) {
                      pending = callback;
                      return null;
                    }
                    if (writeFailure != null) {
                      callback.failed(writeFailure);
                    } else {
                      committed = true;
                      callback.succeeded();
                    }
                    return null;
                  default:
                    throw new UnsupportedOperationException(name);
                }
              });
    }
  }

  /** A {@link TraceSegment} fake counting {@code effectivelyBlocked()} calls. */
  static final class FakeSegment {
    final TraceSegment proxy;
    int effectivelyBlockedCalls;
    RuntimeException failure;

    FakeSegment() {
      this.proxy =
          proxy(
              TraceSegment.class,
              (name, args) -> {
                if ("effectivelyBlocked".equals(name)) {
                  effectivelyBlockedCalls++;
                  if (failure != null) {
                    throw failure;
                  }
                  return null;
                }
                throw new UnsupportedOperationException(name);
              });
    }
  }
}
