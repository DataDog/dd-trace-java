package datadog.trace.instrumentation.selenium;

import datadog.trace.api.civisibility.InstrumentationTestBridge;
import datadog.trace.api.civisibility.domain.TestContext;
import org.openqa.selenium.NoSuchSessionException;
import org.openqa.selenium.WebDriver;

public class SeleniumTestListener implements InstrumentationTestBridge.TestListener {

  public static final InstrumentationTestBridge.TestListener INSTANCE = new SeleniumTestListener();

  @Override
  public void beforeTestEnd(TestContext testContext) {
    WebDriver driver = testContext.get(WebDriver.class);
    if (driver == null) {
      return;
    }

    try {
      driver.getCurrentUrl();
    } catch (NoSuchSessionException e) {
      // the driver was closed before the test ended
      return;
    }

    SeleniumUtils.beforePageClose(driver);
  }
}
