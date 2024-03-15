package datadog.trace.instrumentation.selenium;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Config;
import datadog.trace.api.civisibility.CIConstants;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.util.Optional;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.openqa.selenium.Capabilities;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;

@AutoService(Instrumenter.class)
public class SeleniumInstrumentation extends InstrumenterModule.CiVisibility
    implements Instrumenter.ForTypeHierarchy {

  public SeleniumInstrumentation() {
    super("ci-visibility", "selenium");
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.openqa.selenium.WebDriver";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".SeleniumUtils"};
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("get").and(takesArguments(String.class)),
        SeleniumInstrumentation.class.getName() + "$GetPageAdvice");
    transformer.applyAdvice(
        named("close").and(takesNoArguments()),
        SeleniumInstrumentation.class.getName() + "$ClosePageAdvice");
    transformer.applyAdvice(
        named("quit").and(takesNoArguments()),
        SeleniumInstrumentation.class.getName() + "$QuitBrowserAdvice");
  }

  public static class GetPageAdvice {
    @Advice.OnMethodExit
    public static void onPageLoadFinish(@Advice.This WebDriver driver) {
      if (!(driver instanceof JavascriptExecutor)) {
        return;
      }

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

      if (SeleniumUtils.injectRumContext((JavascriptExecutor) driver, span)) {
        span.setTag(Tags.TEST_IS_RUM_ACTIVE, true);
      }
    }
  }

  public static class ClosePageAdvice {
    @Advice.OnMethodEnter
    public static void beforePageClose(@Advice.This WebDriver driver) {
      if (!(driver instanceof JavascriptExecutor)) {
        return;
      }

      SeleniumUtils.stopRumSession((JavascriptExecutor) driver);

      try {
        Thread.sleep(Config.get().getCiVisibilityRumFlushWaitMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  public static class QuitBrowserAdvice {
    @Advice.OnMethodEnter
    public static void beforeBrowserQuit(@Advice.This WebDriver driver) {
      if (!(driver instanceof JavascriptExecutor)) {
        return;
      }

      for (String windowHandle : driver.getWindowHandles()) {
        driver.switchTo().window(windowHandle);
        SeleniumUtils.stopRumSession((JavascriptExecutor) driver);
      }

      try {
        Thread.sleep(Config.get().getCiVisibilityRumFlushWaitMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
