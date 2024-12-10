package datadog.trace.api.iast;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public interface IastModule {

  Logger LOG = LoggerFactory.getLogger(IastModule.class);

  default void onUnexpectedException(final String message, final Throwable error) {
    LOG.debug(message, error);
  }

  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.TYPE)
  @interface OptOut {}
}
