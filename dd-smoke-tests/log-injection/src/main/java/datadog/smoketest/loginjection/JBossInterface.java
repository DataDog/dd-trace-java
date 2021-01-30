package datadog.smoketest.loginjection;

import org.jboss.logging.Logger;

public class JBossInterface extends BaseApplication {
  private static final Logger log = Logger.getLogger(JBossInterface.class);

  public static void main(String[] args) throws InterruptedException {
    JBossInterface mainInstance = new JBossInterface();
    mainInstance.run();
  }

  @Override
  public void doLog(String message) {
    log.info(message);
  }
}
