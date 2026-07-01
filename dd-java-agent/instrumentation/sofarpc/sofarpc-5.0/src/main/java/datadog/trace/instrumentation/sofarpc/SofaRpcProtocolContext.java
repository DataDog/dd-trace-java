package datadog.trace.instrumentation.sofarpc;

/** Thread-local carrier for the SOFA RPC transport protocol name. */
public final class SofaRpcProtocolContext {

  private static final ThreadLocal<String> PROTOCOL = new ThreadLocal<>();

  private SofaRpcProtocolContext() {}

  public static void set(String protocol) {
    PROTOCOL.set(protocol);
  }

  public static String get() {
    return PROTOCOL.get();
  }

  public static void clear() {
    PROTOCOL.remove();
  }
}
