package datadog.smoketest.loginjection;

import java.util.logging.Logger;

public class JULInterface extends BaseApplication {
  private static final Logger log = Logger.getLogger(JULInterface.class.getName());

  public static void main(String[] args) throws InterruptedException {
    JULInterface mainInstance = new JULInterface();
    mainInstance.run();
  }

  @Override
  public void doLog(String message) {
    log.info(message);
  }
}
