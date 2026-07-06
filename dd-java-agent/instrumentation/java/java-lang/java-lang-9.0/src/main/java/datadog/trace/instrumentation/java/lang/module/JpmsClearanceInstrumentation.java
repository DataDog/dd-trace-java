package datadog.trace.instrumentation.java.lang.module;

import static datadog.trace.bootstrap.instrumentation.java.module.JpmsHelper.logFailedToOpen;
import static datadog.trace.bootstrap.instrumentation.java.module.JpmsHelper.logNoNamedModule;
import static datadog.trace.bootstrap.instrumentation.java.module.JpmsHelper.shouldBeOpened;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import com.google.auto.service.AutoService;
import datadog.environment.JavaVirtualMachine;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.java.module.JpmsHelper;
import java.util.Collection;
import java.util.Set;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/**
 * Generic instrumenter module that advises the constructor of each registered trigger class to open
 * its enclosing module once. Marked {@link Instrumenter.ForBootstrap} because some trigger classes
 * (e.g. {@code InetAddress}) reside in the bootstrap classloader; the annotation is applied
 * conservatively since the set of trigger classes is not known until runtime.
 */
@AutoService(InstrumenterModule.class)
public class JpmsClearanceInstrumentation extends InstrumenterModule
    implements Instrumenter.ForConfiguredTypes,
        Instrumenter.ForBootstrap,
        Instrumenter.HasMethodAdvice {
  public JpmsClearanceInstrumentation() {
    super("java-module");
  }

  @Override
  public boolean isEnabled() {
    return super.isEnabled() && JavaVirtualMachine.isJavaVersionAtLeast(9);
  }

  @Override
  public boolean isApplicable(Set<TargetSystem> enabledSystems) {
    return true; // not directly linked to a target system
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(isConstructor(), getClass().getName() + "$OpenModuleAdvice");
  }

  @Override
  public Collection<String> configuredMatchingTypes() {
    return JpmsHelper.getAllTriggers();
  }

  public static class OpenModuleAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.This(typing = Assigner.Typing.DYNAMIC) Object self) {
      final Class<?> cls = self.getClass();
      if (shouldBeOpened(cls)) {
        final Module module = cls.getModule();
        final String pkg = cls.getPackageName();
        if (module != null) {
          try {
            // This call must be inlined into the constructor of the class belonging to that
            // package because the JDK verifies the caller belongs to the module being opened.
            // Moving this to a helper method will not work.
            module.addOpens(pkg, JpmsHelper.class.getModule());
            final ClassLoader loader = cls.getClassLoader();
            if (loader != null) {
              module.addOpens(pkg, loader.getUnnamedModule());
            }
          } catch (Throwable t) {
            logFailedToOpen(pkg, t);
          }
        } else {
          logNoNamedModule(cls);
        }
      }
    }
  }
}
