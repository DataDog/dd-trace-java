package datadog.trace.api.iast;

import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Encapsulation for the IAST context, */
public interface IastContext {

  /**
   * Get the tainted objects dictionary linked to the context, since we have no visibility over the
   * {@code TaintedObject} class from here, we use a dirty generics hack.
   */
  @Nonnull
  <TO> TO getTaintedObjects();

  /**
   * Some scala instrumentations failed with public static methods inside an interface, that's the
   * reason behind an inner class.
   */
  abstract class Provider {

    private Provider() {}

    @Nullable
    public static IastContext get() {
      return get(AgentTracer.activeSpan());
    }

    @Nullable
    public static IastContext get(final AgentSpan span) {
      if (span == null) {
        return null;
      }
      return get(span.getRequestContext());
    }

    @Nullable
    public static IastContext get(final RequestContext reqCtx) {
      if (reqCtx == null) {
        return null;
      }
      return reqCtx.getData(RequestContextSlot.IAST);
    }
  }
}
