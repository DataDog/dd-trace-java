package datadog.trace.instrumentation.mule4;

import static datadog.trace.instrumentation.mule4.JpmsAdvisingHelper.ALREADY_PROCESSED_CACHE;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

public class JpmsClearanceAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void openOnReturn(@Advice.This(typing = Assigner.Typing.DYNAMIC) Object self) {
    final Class<?> cls = self.getClass();
    if (ALREADY_PROCESSED_CACHE.get(cls).compareAndSet(false, true)) {
      final Module module = cls.getModule();
      if (module != null) {
        try {
          // This call needs imperatively to be done from the same module we're adding exports
          // because the jdk is checking that the caller belongs to the same module.
          // The code of this advice is getting inlined into the constructor of the class belonging
          // to that package so it will work. Moving the same to a helper won't.
          module.addExports(cls.getPackageName(), module.getClassLoader().getUnnamedModule());
        } catch (Throwable ignored) {
        }
      }
    }
  }
}
