package datadog.trace.instrumentation.selenium;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.civisibility.InstrumentationTestBridge;
import datadog.trace.api.civisibility.domain.TestContext;
import java.util.Set;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.openqa.selenium.WebDriver;

@AutoService(InstrumenterModule.class)
public class SeleniumInstrumentation extends InstrumenterModule.CiVisibility
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

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
    return new String[] {
      packageName + ".SeleniumUtils", packageName + ".SeleniumTestListener",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isConstructor(), SeleniumInstrumentation.class.getName() + "$InjectTestListener");
    transformer.applyAdvice(
        named("get").and(takesArguments(String.class)),
        SeleniumInstrumentation.class.getName() + "$GetPageAdvice");
    transformer.applyAdvice(
        named("close").and(takesNoArguments()),
        SeleniumInstrumentation.class.getName() + "$ClosePageAdvice");
    transformer.applyAdvice(
        named("quit").and(takesNoArguments()),
        SeleniumInstrumentation.class.getName() + "$QuitPageAdvice");
  }

  public static class InjectTestListener {
    @Advice.OnMethodExit
    public static void afterWebDriverCreated() {
      InstrumentationTestBridge.registerListener(SeleniumTestListener.INSTANCE);
    }
  }

  public static class GetPageAdvice {
    @Advice.OnMethodExit
    public static void afterPageLoad(@Advice.This WebDriver driver) {
      TestContext testContext = InstrumentationTestBridge.getCurrentTestContext();
      if (testContext != null) {
        testContext.set(WebDriver.class, driver);
      }
      SeleniumUtils.afterPageOpen(driver);
    }
  }

  public static class ClosePageAdvice {
    @Advice.OnMethodEnter
    public static void beforePageClose(@Advice.This WebDriver driver) {
      SeleniumUtils.beforePageClose(driver);
    }
  }

  public static class QuitPageAdvice {
    @Advice.OnMethodEnter
    public static void beforeBrowserQuit(@Advice.This WebDriver driver) {
      Set<String> handles = driver.getWindowHandles();
      if (handles == null) {
        return;
      }
      for (String handle : handles) {
        WebDriver window = driver.switchTo().window(handle);
        SeleniumUtils.beforePageClose(window);
      }
    }
  }
}
