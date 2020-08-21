package datadog.trace.logging;

/** A factory for creating {@link LoggerHelper} instances. */
public abstract class LoggerHelperFactory {
  /**
   * Create a {@link LoggerHelper} for the given {@code name}.
   *
   * @param name the name of the {@code logger}
   * @return the {@link LoggerHelper} for the given name
   */
  public abstract LoggerHelper loggerHelperForName(String name);
}
