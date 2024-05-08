package datadog.trace.instrumentation.selenium;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;

import datadog.trace.api.Config;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.civisibility.CIConstants;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Optional;
import java.util.Properties;
import javax.annotation.Nullable;
import org.openqa.selenium.Capabilities;
import org.openqa.selenium.Cookie;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.remote.RemoteWebDriver;

public abstract class SeleniumUtils {

  public static final String SELENIUM_VERSION = getSeleniumVersion();

  private static final String RUM_CONTEXT_COOKIE_NAME = "datadog-ci-visibility-test-execution-id";

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

  public static void afterPageOpen(WebDriver driver) {
    AgentSpan span = activeSpan();
    if (span == null) {
      // no active span
      return;
    }

    String spanType = span.getSpanType();
    if (spanType == null || !spanType.contentEquals(InternalSpanTypes.TEST)) {
      // not in a test
      return;
    }

    injectRumContext(driver, span.getTraceId());
  }

  private static void injectRumContext(WebDriver driver, DDTraceId traceId) {
    WebDriver.Options options = driver.manage();
    // options can be null if the driver has not finished initializing yet
    // (which is the case when the driver's home page is opened)
    if (options == null) {
      return;
    }
    try {
      String domain = getCookieDomain(driver.getCurrentUrl());
      options.addCookie(new Cookie(RUM_CONTEXT_COOKIE_NAME, traceId.toString(), domain, "/", null));
    } catch (MalformedURLException e) {
      // could be "about:blank" or other similar pages,
      // trying to set a cookie will cause exceptions
    }
  }

  private static void clearRumContext(WebDriver driver) {
    WebDriver.Options options = driver.manage();
    // options can be null if the driver has not finished initializing yet
    // (which is the case when the driver's home page is opened)
    if (options != null) {
      options.deleteCookieNamed(RUM_CONTEXT_COOKIE_NAME);
    }
  }

  @SuppressForbidden
  static String getCookieDomain(String urlString) throws MalformedURLException {
    URL url = new URL(urlString);
    String host = url.getHost();
    if (isIPV4Address(host)) {
      return null;
    }

    int idx = host.length();
    int tokenCount = 0;
    while (tokenCount < 2 && idx > 0) {
      idx = host.lastIndexOf('.', idx - 1);
      tokenCount++;
    }
    return idx == -1 ? null : host.substring(idx + 1);
  }

  @SuppressForbidden
  static boolean isIPV4Address(String host) {
    if (host == null) {
      return false;
    }
    String[] tokens = host.split("\\.");
    if (tokens.length != 4) {
      return false;
    }
    for (String token : tokens) {
      try {
        int value = Integer.parseInt(token);
        if (value < 0 || value > 255) {
          return false;
        }
      } catch (NumberFormatException e) {
        return false;
      }
    }
    return true;
  }

  public static void beforePageClose(WebDriver driver) {
    AgentSpan span = activeSpan();
    if (span == null) {
      // no active span
      return;
    }

    String spanType = span.getSpanType();
    if (spanType == null || !spanType.contentEquals(InternalSpanTypes.TEST)) {
      // not in a test
      return;
    }

    span.setTag(Tags.TEST_TYPE, "browser");
    span.setTag(Tags.TEST_BROWSER_DRIVER, CIConstants.SELENIUM_BROWSER_DRIVER);
    span.setTag(Tags.TEST_BROWSER_DRIVER_VERSION, SeleniumUtils.SELENIUM_VERSION);

    Capabilities capabilities = SeleniumUtils.getCapabilities(driver);
    if (capabilities != null) {
      String browserName = capabilities.getBrowserName();
      String browserVersion =
          String.valueOf(
              Optional.ofNullable(capabilities.getCapability("browserVersion"))
                  .orElse(Optional.ofNullable(capabilities.getCapability("version")).orElse("")));
      span.setTag(Tags.TEST_BROWSER_NAME, browserName);
      span.setTag(Tags.TEST_BROWSER_VERSION, browserVersion);
    }

    if (driver instanceof JavascriptExecutor
        && SeleniumUtils.isRumAvailable((JavascriptExecutor) driver)) {
      span.setTag(Tags.TEST_IS_RUM_ACTIVE, true);
      stopRumSession((JavascriptExecutor) driver);
    }

    clearRumContext(driver);
  }

  @Nullable
  private static Capabilities getCapabilities(WebDriver driver) {
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

  private static boolean isRumAvailable(JavascriptExecutor js) {
    return (boolean) js.executeScript("return !!window.DD_RUM;");
  }

  private static void stopRumSession(JavascriptExecutor js) {
    js.executeScript(
        "if (window.DD_RUM && window.DD_RUM.stopSession) { window.DD_RUM.stopSession(); }");
    try {
      Thread.sleep(Config.get().getCiVisibilityRumFlushWaitMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
