package datadog.smoketest.loginjection;

import org.apache.log4j.Logger;

public class Log4j1Interface extends BaseApplication {
  private static final Logger log = Logger.getLogger(Log4j1Interface.class);

  public static void main(String[] args) throws InterruptedException {
    Log4j1Interface mainInstance = new Log4j1Interface();
    mainInstance.run();
  }

  @Override
  public void doLog(String message) {
    log.info(message);
  }
}
