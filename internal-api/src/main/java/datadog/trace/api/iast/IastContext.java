package datadog.trace.api.iast;

import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
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
  <TO> TO getTaintedObjects();

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
     * Gets an active IAST context, there are two possibilities:
     *
     * <ul>
     *   <li>dd.iast.context.mode=GLOBAL: It returns the global IAST context instance
     *   <li>dd.iast.context.mode=REQUEST: Fetches the active request context and extracts the IAST
     *       context, {@code null} if there is no active request context
     * </ul>
     */
    @Nullable
    public abstract IastContext resolve();

    /** Builds a new context to be scoped to the request */
    public abstract IastContext buildRequestContext();

    /** Release the current request context, e.g. free resources, add objects to pools, ... */
    public abstract void releaseRequestContext(@Nonnull IastContext context);

    /**
     * Gets the current active IAST context, if no provider is configured this method defaults to
     * fetching the current request context
     *
     * @see Provider#resolve()
     */
    @Nullable
    public static IastContext get() {
      if (INSTANCE == null) {
        return get(AgentTracer.activeSpan());
      }
      return INSTANCE.resolve();
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
