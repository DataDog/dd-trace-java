package datadog.trace.logging.ddlogger;

import datadog.trace.logging.LogLevel;
import datadog.trace.logging.LogLevelSwitcher;
import datadog.trace.logging.LoggerHelper;
import datadog.trace.logging.LoggerHelperFactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Marker;

public final class SwitchableLogLevelFactory extends LoggerHelperFactory
    implements LogLevelSwitcher {
  private final LoggerHelperFactory delegate;
  private final AtomicReference<LogLevel> override;

  public SwitchableLogLevelFactory(LoggerHelperFactory delegate) {
    this(new AtomicReference<LogLevel>(), delegate);
  }

  public SwitchableLogLevelFactory(
      AtomicReference<LogLevel> override, LoggerHelperFactory delegate) {
    this.delegate = delegate;
    this.override = override;
  }

  @Override
  public void switchLevel(LogLevel level) {
    Opaque.setLevel(override, level);
  }

  @Override
  public void restore() {
    Opaque.setLevel(override, null);
  }

  private static final class Opaque {
    // Try to get handles to `getOpaque` and `setOpaque` since the reading and writing
    // of the override level does not need any  guarantees of memory ordering between
    // threads, and those should be the fastest.
    private static final MethodHandle GET;
    private static final MethodHandle SET;

    static {
      MethodHandles.Lookup l = MethodHandles.publicLookup();
      Class<?> arClass = AtomicReference.class;
      Class<?> oClass = Object.class;
      MethodHandle get = null;
      MethodHandle set = null;
      try {
        get = l.findVirtual(arClass, "getOpaque", MethodType.methodType(oClass));
        set = l.findVirtual(arClass, "setOpaque", MethodType.methodType(void.class, oClass));
      } catch (Throwable t1) {
        try {
          get = l.findVirtual(arClass, "get", MethodType.methodType(oClass));
          set = l.findVirtual(arClass, "lazySet", MethodType.methodType(void.class, oClass));
        } catch (Throwable t2) {
          throw new ExceptionInInitializerError(t2);
        }
      }
      GET = get;
      SET = set;
    }

    private static LogLevel getLevel(AtomicReference<LogLevel> ar) {
      try {
        return (LogLevel) GET.invoke(ar);
      } catch (Throwable t) {
        return ar.get();
      }
    }

    private static void setLevel(AtomicReference<LogLevel> ar, LogLevel level) {
      try {
        SET.invoke(ar, (Object) level);
      } catch (Throwable t) {
        ar.lazySet(level);
      }
    }
  }

  @Override
  public LoggerHelper loggerHelperForName(String name) {
    return new Helper(override, delegate.loggerHelperForName(name));
  }

  static final class Helper extends LoggerHelper {
    private final AtomicReference<LogLevel> override;
    private final LoggerHelper delegate;

    private Helper(AtomicReference<LogLevel> override, LoggerHelper delegate) {
      this.override = override;
      this.delegate = delegate;
    }

    @Override
    public boolean enabled(LogLevel level, Marker marker) {
      // We can only make delegates more verbose
      if (delegate.enabled(level, marker)) {
        return true;
      }

      LogLevel levelOverride = Opaque.getLevel(override);
      return null != levelOverride && level.isEnabled(levelOverride);
    }

    @Override
    public void log(LogLevel level, String message, Throwable t) {
      delegate.log(level, message, t);
    }
  }
}
