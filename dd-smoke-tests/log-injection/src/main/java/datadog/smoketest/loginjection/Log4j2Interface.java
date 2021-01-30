package datadog.smoketest.loginjection;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class Log4j2Interface extends BaseApplication {
  private static final Logger log = LogManager.getLogger(Log4j2Interface.class);

  public static void main(String[] args) throws InterruptedException {
    Log4j2Interface mainInstance = new Log4j2Interface();
    mainInstance.run();
  }

  @Override
  public void doLog(String message) {
    log.info(message);
  }
}
