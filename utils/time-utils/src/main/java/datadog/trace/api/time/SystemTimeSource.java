package datadog.trace.api.time;

import java.lang.reflect.InvocationTargetException;

public class SystemTimeSource implements TimeSource {
  public static final TimeSource INSTANCE = new SystemTimeSource();

  private final TimeSource delegate;

  private SystemTimeSource() {
    TimeSource target;
    try {
      // Platform is in internal-api so we can't use it to check for Java 8+
      // Reflection to avoid loading the class on Java  7
      target =
          (TimeSource)
              Class.forName("datadog.trace.api.time.Java8TimeSource")
                  .getDeclaredConstructor()
                  .newInstance();
    } catch (InstantiationException
        | ClassNotFoundException
        | NoSuchMethodException
        | IllegalAccessException
        | InvocationTargetException
        | NoClassDefFoundError e) {
      target = new Java7TimeSource();
    }

    delegate = target;
  }

  @Override
  public long getNanoTicks() {
    return delegate.getNanoTicks();
  }

  @Override
  public long getCurrentTimeMillis() {
    return delegate.getCurrentTimeMillis();
  }

  @Override
  public long getCurrentTimeMicros() {
    return delegate.getCurrentTimeMicros();
  }

  @Override
  public long getCurrentTimeNanos() {
    return delegate.getCurrentTimeNanos();
  }
}
