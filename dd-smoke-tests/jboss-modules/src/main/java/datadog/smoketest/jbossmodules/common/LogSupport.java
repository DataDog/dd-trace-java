package datadog.smoketest.jbossmodules.common;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public abstract class LogSupport {
  protected final Log log = LogFactory.getLog(getClass());
}
