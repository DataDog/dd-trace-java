package datadog.smoketest.loginjection;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public class JCLInterface extends BaseApplication {
  private static final Log log = LogFactory.getLog(JCLInterface.class);

  public static void main(String[] args) throws InterruptedException {
    JCLInterface mainInstance = new JCLInterface();
    mainInstance.run();
  }

  @Override
  public void doLog(String message) {
    log.info(message);
  }
}
