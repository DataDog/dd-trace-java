package org.example;

import com.gargoylesoftware.htmlunit.BrowserVersion;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.htmlunit.HtmlUnitDriver;

public class TestSucceedSelenium {

  @Test
  public void test_succeed() {
    WebDriver driver = new HtmlUnitDriver(BrowserVersion.CHROME, true);
    driver.get(System.getProperty("selenium-test.dummy-page-url"));
    Assertions.assertEquals("Selenium Integration Test", driver.getTitle());
    driver.close();
  }
}
