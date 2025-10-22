package datadog.smoketest.jbossmodules.common;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

@SuppressFBWarnings("CT_CONSTRUCTOR_THROW")
public abstract class LogSupport {
  protected final Log log = LogFactory.getLog(getClass());
}
