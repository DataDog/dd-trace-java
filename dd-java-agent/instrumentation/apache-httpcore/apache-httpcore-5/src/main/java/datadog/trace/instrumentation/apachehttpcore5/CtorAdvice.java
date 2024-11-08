package datadog.trace.instrumentation.apachehttpcore5;

import net.bytebuddy.asm.Advice;

public class CtorAdvice {
  @Advice.OnMethodExit()
  public static void afterCtor() {
    System.out.println("CtorAdvice.afterCtor");
    //    final PropagationModule module = InstrumentationBridge.PROPAGATION;
    //    if (module != null) {
    //      module.taintObjectIfTainted(self, argument);
    //    }
  }
}
