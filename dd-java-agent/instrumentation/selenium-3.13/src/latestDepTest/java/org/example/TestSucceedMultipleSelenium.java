package org.example;

import com.gargoylesoftware.htmlunit.BrowserVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WindowType;
import org.openqa.selenium.htmlunit.HtmlUnitDriver;

public class TestSucceedMultipleSelenium {

  private static WebDriver driver;

  @BeforeAll
  public static void setUp() {
    driver = new HtmlUnitDriver(BrowserVersion.CHROME, true);
  }

  @Test
  public void test_succeed() {
    WebDriver window = driver.switchTo().newWindow(WindowType.WINDOW);
    window.get(System.getProperty("selenium-test.dummy-page-url"));
    Assertions.assertEquals("Selenium Integration Test", window.getTitle());
  }

  @Test
  public void test_succeed_another() {
    WebDriver window = driver.switchTo().newWindow(WindowType.WINDOW);
    window.get(System.getProperty("selenium-test.dummy-page-url"));
    Assertions.assertEquals("Selenium Integration Test", window.getTitle());
  }

  @AfterAll
  public static void tearDown() {
    driver.quit();
  }
}
