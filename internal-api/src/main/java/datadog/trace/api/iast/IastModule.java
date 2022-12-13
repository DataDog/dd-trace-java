package datadog.trace.api.iast;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public interface IastModule {

  Logger LOG = LoggerFactory.getLogger(IastModule.class);

  default void onUnexpectedException(final String message, final Throwable error) {
    LOG.warn(message, error);
  }
}
