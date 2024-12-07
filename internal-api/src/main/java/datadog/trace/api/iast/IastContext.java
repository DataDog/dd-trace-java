package datadog.trace.api.iast;

import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.iast.taint.TaintedObjects;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.io.Closeable;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Encapsulation for the IAST context, */
public interface IastContext extends Closeable {

  /**
   * Get the tainted objects dictionary linked to the context, since we have no visibility over the
   * {@code TaintedObject} class from here, we use a dirty generics hack.
   */
  @Nonnull
  TaintedObjects getTaintedObjects();

  enum Mode {
    GLOBAL,
    REQUEST
  }

  abstract class Provider {

    private static Provider INSTANCE;

    public static void register(@Nonnull final Provider instance) {
      INSTANCE = instance;
    }

    /**
     * Gets the current tainted objects, there are two possibilities:
     *
     * <ul>
     *   <li>dd.iast.context.mode=GLOBAL: It returns the global IAST tainted objects instance
     *   <li>dd.iast.context.mode=REQUEST: Fetches the active request context and extracts the IAST
     *       tainted objects, {@code null} if there is no active request context
     * </ul>
     */
    @Nullable
    public abstract TaintedObjects resolveTaintedObjects();

    /** Builds a new context to be scoped to the request */
    public abstract IastContext buildRequestContext();

    /** Release the current request context, e.g. free resources, add objects to pools, ... */
    public abstract void releaseRequestContext(@Nonnull IastContext context);

    /**
     * Gets the current active tainted objects dictionary, if no provider is configured this method
     * defaults to fetching the dictionary included in the current request context
     *
     * @see Provider#taintedObjects()
     */
    @Nullable
    public static TaintedObjects taintedObjects() {
      if (INSTANCE == null) {
        final IastContext ctx = get();
        return ctx == null ? null : ctx.getTaintedObjects();
      }
      return INSTANCE.resolveTaintedObjects();
    }

    /** Gets the current active IAST context */
    @Nullable
    public static IastContext get() {
      return get(AgentTracer.activeSpan());
    }

    /** Gets the current IAST context associated with the request context inside the span */
    @Nullable
    public static IastContext get(@Nullable final AgentSpan span) {
      if (span == null) {
        return null;
      }
      final RequestContext reqCtx = span.getRequestContext();
      if (reqCtx == null) {
        return null;
      }
      return reqCtx.getData(RequestContextSlot.IAST);
    }
  }
}
