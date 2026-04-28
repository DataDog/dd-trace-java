package datadog.trace.instrumentation.java.lang.module;

import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import com.google.auto.service.AutoService;
import datadog.environment.JavaVirtualMachine;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.java.module.JpmsHelper;
import java.util.Set;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/**
 * Generic instrumenter module that will advice the constructor of each known classes in order to
 * open once their module. This is marked for bootstrap even if it's not for sure, but we cannot
 * know in advance (depends to the instrumented types and today we are instrumenting InetAddress
 * that's in the bootstrap).
 */
@AutoService(InstrumenterModule.class)
public class JpmsClearanceInstrumentation extends InstrumenterModule
    implements Instrumenter.ForKnownTypes, Instrumenter.ForBootstrap, Instrumenter.HasMethodAdvice {
  public JpmsClearanceInstrumentation() {
    super("java-module");
  }

  @Override
  public boolean isEnabled() {
    return super.isEnabled() && JavaVirtualMachine.isJavaVersionAtLeast(9);
  }

  @Override
  public boolean isApplicable(Set<TargetSystem> enabledSystems) {
    return true; // not directly linked ot a target system
  }

  @Override
  public String[] knownMatchingTypes() {
    return JpmsHelper.getAllTriggers().toArray(new String[0]);
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(isConstructor(), getClass().getName() + "$OpenModuleAdvice");
  }

  public static class OpenModuleAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.This(typing = Assigner.Typing.DYNAMIC) Object self) {
      final Class<?> cls = self.getClass();
      if (JpmsHelper.shouldBeOpened(cls)) {
        final Module module = cls.getModule();
        if (module != null) {
          try {
            // This call needs imperatively to be done from the same module we're adding exports
            // because the jdk is checking that the caller belongs to the same module.
            // The code of this advice is getting inlined into the constructor of the class
            // belonging
            // to that package so it will work. Moving the same to a helper won't.
            module.addOpens(cls.getPackageName(), JpmsHelper.class.getModule());
          } catch (Throwable t) {
            JpmsHelper.LOGGER.debug(
                "Unable to open package {} to the unnamed module", cls.getPackageName(), t);
          }
        }
      }
    }
  }
}
