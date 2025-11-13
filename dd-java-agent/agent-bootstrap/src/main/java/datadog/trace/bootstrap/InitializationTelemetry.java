package datadog.trace.bootstrap;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Thread safe wrapper around BootstrapInitializationTelemetry used inside the Datadog ClassLoader.
 *
 * <p>Right now, this is needed because of the build separation between the two portions of the
 * bootstrap. We should consider adjusting the build to allow Agent et al to reference
 * BootstrapInitializationTelemetry, then we could remove this proxy.
 */
public abstract class InitializationTelemetry {
  /** Returns a proxy around a BoostrapInitializationTelemetry object */
  public static final InitializationTelemetry proxy(Object bootstrapInitTelemetry) {
    if (bootstrapInitTelemetry == null) {
      return InitializationTelemetry.noOpInstance();
    } else {
      return new BootstrapProxy(bootstrapInitTelemetry);
    }
  }

  /** Returns a singleton of the no op InitializationTelemetry */
  public static final InitializationTelemetry noOpInstance() {
    return NoOp.INSTANCE;
  }

  /**
   * Indicates that an abort condition occurred during the bootstrapping process. Abort conditions
   * are assumed to leave the bootstrapping process incomplete. {@link #markIncomplete()}
   */
  public abstract void onAbort(String reasonCode);

  /**
   * Indicates that an exception occurred during the bootstrapping process By default the exception
   * is assumed to NOT have fully stopped the initialization of the tracer.
   *
   * <p>If this exception stops the core bootstrapping of the tracer, then {@link #markIncomplete()}
   * should also be called.
   */
  public abstract void onError(Throwable t);

  /**
   * Indicates an exception that occurred during the bootstrapping process that left initialization
   * incomplete. Equivalent to calling {@link #onError(Throwable)} and {@link #markIncomplete()}
   */
  public abstract void onFatalError(Throwable t);

  /**
   * Indicates that an exception conditional occurred during the bootstrapping process. By default
   * the exceptional condition is assumed to NOT have fully stopped the initialization of the
   * tracer.
   *
   * <p>If this exception stops the core bootstrapping of the tracer, then {@link #markIncomplete()}
   * should also be called.
   */
  public abstract void onError(String reasonCode);

  /**
   * Marks bootstrapping of tracer as an incomplete Should only be called when a core (e.g.
   * non-optional) component fails to initialize
   */
  public abstract void markIncomplete();

  /** No telemetry - used for delayed initialization outside bootstrap invocation */
  static final class NoOp extends InitializationTelemetry {
    static final NoOp INSTANCE = new NoOp();

    NoOp() {}

    @Override
    public void onAbort(String reasonCode) {}

    @Override
    public void onError(String reasonCode) {}

    @Override
    public void onError(Throwable t) {}

    @Override
    public void onFatalError(Throwable t) {}

    @Override
    public void markIncomplete() {}
  }

  /** Reflective proxy to BootstrapInitializationTelemetry */
  static final class BootstrapProxy extends InitializationTelemetry {
    private final Object bootstrapInitTelemetry;
    private volatile MethodHandle bmh_onAbortString;
    private volatile MethodHandle bmh_onErrorString;
    private volatile MethodHandle bmh_onErrorThrowable;
    private volatile MethodHandle bmh_onFatalErrorThrowable;
    private volatile MethodHandle bmh_markIncomplete;

    // DQH - Decided not to eager access MethodHandles, since exceptions are uncommon
    // However, MethodHandles are cached on lookup

    /**
     * @param bootstrapInitTelemetry - non-null BootstrapInitializationTelemetry
     */
    BootstrapProxy(final Object bootstrapInitTelemetry) {
      this.bootstrapInitTelemetry = bootstrapInitTelemetry;
    }

    @Override
    public void onAbort(String reasonCode) {
      MethodHandle bmh_onAbortString = this.bmh_onAbortString;
      if (bmh_onAbortString == null) {
        bmh_onAbortString = findBoundHandle("onAbort", String.class);
        this.bmh_onAbortString = bmh_onAbortString;
      }
      if (bmh_onAbortString != null) {
        try {
          bmh_onAbortString.invokeExact(reasonCode);
        } catch (Throwable t) {
          // ignore
        }
      }
    }

    @Override
    public void onError(String reasonCode) {
      MethodHandle bmh_onErrorString = this.bmh_onErrorString;
      if (bmh_onErrorString == null) {
        bmh_onErrorString = findBoundHandle("onError", String.class);
        this.bmh_onErrorString = bmh_onErrorString;
      }
      if (bmh_onErrorString != null) {
        try {
          bmh_onErrorString.invokeExact(reasonCode);
        } catch (Throwable t) {
          // ignore
        }
      }
    }

    @Override
    public void onError(Throwable cause) {
      MethodHandle bmh_onErrorThrowable = this.bmh_onErrorThrowable;
      if (bmh_onErrorThrowable == null) {
        bmh_onErrorThrowable = findBoundHandle("onError", Throwable.class);
        this.bmh_onErrorThrowable = bmh_onErrorThrowable;
      }
      if (bmh_onErrorThrowable != null) {
        try {
          bmh_onErrorThrowable.invokeExact(cause);
        } catch (Throwable t) {
          // ignore
        }
      }
    }

    @Override
    public void onFatalError(Throwable cause) {
      MethodHandle bmh_onFatalErrorThrowable = this.bmh_onFatalErrorThrowable;
      if (bmh_onFatalErrorThrowable == null) {
        bmh_onFatalErrorThrowable = findBoundHandle("onFatalError", Throwable.class);
        this.bmh_onFatalErrorThrowable = bmh_onFatalErrorThrowable;
      }
      if (bmh_onFatalErrorThrowable != null) {
        try {
          bmh_onFatalErrorThrowable.invokeExact(cause);
        } catch (Throwable t) {
          // ignore
        }
      }
    }

    @Override
    public void markIncomplete() {
      MethodHandle bmh_markIncomplete = this.bmh_markIncomplete;
      if (bmh_markIncomplete == null) {
        bmh_markIncomplete = findBoundHandle("markIncomplete");
        this.bmh_markIncomplete = bmh_markIncomplete;
      }
      if (bmh_markIncomplete != null) {
        try {
          bmh_markIncomplete.invokeExact();
        } catch (Throwable t) {
          // ignore
        }
      }
    }

    private final MethodHandle findBoundHandle(String name, Class<?> paramType) {
      try {
        MethodHandle virtualHandle =
            MethodHandles.publicLookup()
                .findVirtual(
                    bootstrapInitTelemetry.getClass(),
                    name,
                    MethodType.methodType(void.class, paramType));

        return virtualHandle.bindTo(bootstrapInitTelemetry);
      } catch (NoSuchMethodException | IllegalAccessException e) {
        return null;
      }
    }

    private final MethodHandle findBoundHandle(String name) {
      try {
        MethodHandle virtualHandle =
            MethodHandles.publicLookup()
                .findVirtual(
                    bootstrapInitTelemetry.getClass(), name, MethodType.methodType(void.class));

        return virtualHandle.bindTo(bootstrapInitTelemetry);
      } catch (NoSuchMethodException | IllegalAccessException e) {
        return null;
      }
    }
  }
}
