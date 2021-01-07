package datadog.smoketest.osgi.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class LogSupport {
  protected final Logger log = LoggerFactory.getLogger(getClass());
}
