package datadog.trace.instrumentation.mule4;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

public class JpmsClearanceAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void openOnReturn(@Advice.This(typing = Assigner.Typing.DYNAMIC) Object self) {
    final Module module = self.getClass().getModule();
    if (module == null || JpmsAdvisingHelper.isModuleAlreadyProcessed(module)) {
      return;
    }
    for (String pn : module.getPackages()) {
      try {
        module.addExports(pn, module.getClassLoader().getUnnamedModule());
      } catch (Throwable ignored) {
      }
    }
  }
}
