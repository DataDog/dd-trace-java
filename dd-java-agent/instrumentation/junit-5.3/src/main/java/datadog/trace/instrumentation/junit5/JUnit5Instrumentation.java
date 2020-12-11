package datadog.trace.instrumentation.junit5;

import static datadog.trace.agent.tooling.ClassLoaderMatcher.hasClassesNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.implementsInterface;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.named;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import java.util.Collections;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.junit.platform.engine.support.hierarchical.SameThreadHierarchicalTestExecutorService;
import org.junit.platform.launcher.Launcher;

@AutoService(Instrumenter.class)
public class JUnit5Instrumentation extends Instrumenter.Tracing {

  public JUnit5Instrumentation() {
    super("junit", "junit-5");
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return implementsInterface(named("org.junit.platform.launcher.Launcher"));
  }

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    // Optimization for expensive typeMatcher.
    return hasClassesNamed("org.junit.platform.launcher.Launcher");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".JUnit5Decorator", packageName + ".TracingListener"};
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return Collections.singletonMap(
        isConstructor(), JUnit5Instrumentation.class.getName() + "$JUnit5Advice");
  }

  @Override
  protected boolean defaultEnabled() {
    return false;
  }

  public static class JUnit5Advice {

    @Advice.OnMethodExit
    public static void addTracingListener(@Advice.This final Launcher launcher) {
      final TracingListener listener = new TracingListener();
      launcher.registerTestExecutionListeners(listener);
    }

    // JUnit 5.3.0 and above
    public static void muzzleCheck(final SameThreadHierarchicalTestExecutorService service) {
      service.invokeAll(null);
    }
  }
}
