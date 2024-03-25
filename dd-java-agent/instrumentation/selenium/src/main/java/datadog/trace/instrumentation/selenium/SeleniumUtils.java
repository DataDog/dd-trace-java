package datadog.trace.instrumentation.selenium;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.Properties;
import javax.annotation.Nullable;
import org.openqa.selenium.Capabilities;
import org.openqa.selenium.Cookie;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.remote.RemoteWebDriver;

public abstract class SeleniumUtils {

  public static final String SELENIUM_VERSION = getSeleniumVersion();

  private SeleniumUtils() {}

  @Nullable
  private static String getSeleniumVersion() {
    try {
      String className = '/' + WebDriver.class.getName().replace('.', '/') + ".class";
      URL classResource = WebDriver.class.getResource(className);
      if (classResource == null) {
        return null;
      }

      String classPath = classResource.toString();
      String manifestPath =
          classPath.substring(0, classPath.lastIndexOf("!") + 1) + "/META-INF/MANIFEST.MF";
      try (InputStream manifestStream = new java.net.URL(manifestPath).openStream()) {
        Properties manifestProperties = new Properties();
        manifestProperties.load(manifestStream);
        return manifestProperties.getProperty("Selenium-Version");
      }

    } catch (Exception e) {
      return null;
    }
  }

  public static void injectRumContext(WebDriver driver, AgentSpan span) {
    WebDriver.Options options = driver.manage();
    if (options != null) {
      // options can be null if the driver is not finished initialization yet
      // (which is the case when the driver's home page is opened)
      options.addCookie(new Cookie("dd_ci_visibility_test_execution_id", span.getTraceId().toString()));
    }
  }

  public static boolean isRumAvailable(JavascriptExecutor js) {
    return (boolean) js.executeScript("return !!window.DD_RUM;");
  }

  public static void stopRumSession(JavascriptExecutor js) {
    js.executeScript(
        "if (window.DD_RUM && window.DD_RUM.stopSession) { window.DD_RUM.stopSession(); }");
  }

  @Nullable
  public static Capabilities getCapabilities(WebDriver driver) {
    if (driver instanceof RemoteWebDriver) {
      return ((RemoteWebDriver) driver).getCapabilities();
    }
    Class<? extends WebDriver> driverClass = driver.getClass();
    try {
      Method getCapabilitiesMethod = driverClass.getMethod("getCapabilities");
      return (Capabilities) getCapabilitiesMethod.invoke(driver);

    } catch (Exception e) {
      return null;
    }
  }
}
