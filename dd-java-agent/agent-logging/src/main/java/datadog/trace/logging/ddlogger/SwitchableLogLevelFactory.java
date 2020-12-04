package datadog.trace.logging.ddlogger;

import datadog.trace.logging.LogLevel;
import datadog.trace.logging.LogLevelSwitcher;
import datadog.trace.logging.LoggerHelper;
import datadog.trace.logging.LoggerHelperFactory;
import java.util.Map;
import org.slf4j.Marker;

public final class SwitchableLogLevelFactory extends LoggerHelperFactory
    implements LogLevelSwitcher {
  private final LoggerHelperFactory delegate;
  private volatile LogLevel override = null;

  public SwitchableLogLevelFactory(LoggerHelperFactory delegate) {
    this.delegate = delegate;
  }

  @Override
  public void switchLevel(LogLevel level) {
    override = level;
  }

  @Override
  public void restore() {
    override = null;
  }

  @Override
  public LoggerHelper loggerHelperForName(String name) {
    return new Helper(delegate.loggerHelperForName(name));
  }

  final class Helper extends LoggerHelper {
    private final LoggerHelper delegate;

    private Helper(LoggerHelper delegate) {
      this.delegate = delegate;
    }

    @Override
    public boolean enabled(LogLevel level, Marker marker) {
      // We can only make delegates more verbose
      if (delegate.enabled(level, marker)) {
        return true;
      }

      LogLevel levelOverride = SwitchableLogLevelFactory.this.override;
      return null != levelOverride && level.isEnabled(levelOverride);
    }

    @Override
    public void log(LogLevel level, String message, Throwable t) {
      delegate.log(level, message, t);
    }
  }

  @Override
  public Map<String, Object> getSettingsDescription() {
    return delegate.getSettingsDescription();
  }
}
