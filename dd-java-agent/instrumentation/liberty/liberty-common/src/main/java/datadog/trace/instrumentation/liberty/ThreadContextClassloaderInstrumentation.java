package datadog.trace.instrumentation.liberty;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.declaresField;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.fieldType;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.ClassloaderConfigurationOverrides;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public class ThreadContextClassloaderInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType,
        Instrumenter.WithTypeStructure,
        Instrumenter.HasMethodAdvice {

  private static final String LIBERTY = "liberty";

  public ThreadContextClassloaderInstrumentation() {
    super(LIBERTY, "liberty-classloading");
  }

  @Override
  public String instrumentedType() {
    return "com.ibm.ws.classloading.internal.ThreadContextClassLoader";
  }

  @Override
  public ElementMatcher<TypeDescription> structureMatcher() {
    // The class name and deployment key are shared by both supported Liberty generations.
    return declaresField(fieldType(String.class).and(named("key")));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".BundleNameHelper",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isConstructor(), getClass().getName() + "$ThreadContextClassloaderAdvice");
  }

  public static class ThreadContextClassloaderAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterConstruct(
        @Advice.This ClassLoader self, @Advice.FieldValue("key") String key) {
      // Bind the stable field instead of an OpenLiberty type so this module stays version-neutral.
      final String name = BundleNameHelper.extractDeploymentName(key);
      if (name != null && !name.isEmpty()) {
        ClassloaderConfigurationOverrides.withPinnedServiceName(self, name);
      }
    }
  }
}
