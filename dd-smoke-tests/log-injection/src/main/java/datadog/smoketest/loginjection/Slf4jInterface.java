package datadog.smoketest.loginjection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Slf4jInterface extends BaseApplication {
  private static final Logger log = LoggerFactory.getLogger(Slf4jInterface.class);

  public static void main(String[] args) throws InterruptedException {
    Slf4jInterface mainInstance = new Slf4jInterface();
    mainInstance.run();
  }

  @Override
  public void doLog(String message) {
    log.info(message);
  }
}
