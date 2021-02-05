package datadog.smoketest.loginjection;

import com.google.common.flogger.FluentLogger;

public class FloggerInterface extends BaseApplication {
  private static final FluentLogger log = FluentLogger.forEnclosingClass();

  public static void main(String[] args) throws InterruptedException {
    FloggerInterface mainInstance = new FloggerInterface();
    mainInstance.run();
  }

  @Override
  public void doLog(String message) {
    log.atInfo().log(message);
  }
}
