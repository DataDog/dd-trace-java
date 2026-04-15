package datadog.trace.instrumentation.sofarpc;

import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;

/**
 * Thread-local carrier for the SOFA RPC transport protocol name and the propagated parent span
 * context. The parent context is extracted by transport-specific instrumentation (e.g.
 * TripleServerInstrumentation reads it from gRPC Metadata) so that ProviderProxyInvokerInstrumentation
 * can start the server span with an explicit parent regardless of whether other instrumentation
 * (e.g. gRPC) is enabled.
 */
public final class SofaRpcProtocolContext {

  private static final ThreadLocal<String> PROTOCOL = new ThreadLocal<>();
  private static final ThreadLocal<AgentSpanContext> PARENT_CONTEXT = new ThreadLocal<>();

  private SofaRpcProtocolContext() {}

  public static void set(String protocol) {
    PROTOCOL.set(protocol);
  }

  public static void setParentContext(AgentSpanContext parentContext) {
    PARENT_CONTEXT.set(parentContext);
  }

  public static String get() {
    return PROTOCOL.get();
  }

  public static AgentSpanContext getParentContext() {
    return PARENT_CONTEXT.get();
  }

  public static void clear() {
    PROTOCOL.remove();
    PARENT_CONTEXT.remove();
  }
}
