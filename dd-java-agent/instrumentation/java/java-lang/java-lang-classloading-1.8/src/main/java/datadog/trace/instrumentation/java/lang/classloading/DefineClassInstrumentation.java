package datadog.trace.instrumentation.java.lang.classloading;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.bootstrap.DatadogClassLoader;
import datadog.trace.bootstrap.instrumentation.classloading.ClassDefining;
import java.security.ProtectionDomain;
import net.bytebuddy.asm.Advice;

/** Updates j.l.ClassLoader to notify the tracer when classes are about to be defined. */
@AutoService(InstrumenterModule.class)
public final class DefineClassInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForBootstrap, Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public DefineClassInstrumentation() {
    super("defineclass");
  }

  @Override
  public boolean isEnabled() {
    // only enable this when memoizing type hierarchies, where it provides the most ROI
    return super.isEnabled() && InstrumenterConfig.get().isResolverMemoizingEnabled();
  }

  @Override
  public String instrumentedType() {
    return "java.lang.ClassLoader";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(
                named("defineClass")
                    .and(
                        takesArguments(
                            String.class,
                            byte[].class,
                            int.class,
                            int.class,
                            ProtectionDomain.class))),
        DefineClassInstrumentation.class.getName() + "$DefineClassAdvice");
  }

  public static class DefineClassAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
        @Advice.This ClassLoader loader,
        @Advice.Argument(1) byte[] bytecode,
        @Advice.Argument(2) int offset,
        @Advice.Argument(3) int length) {
      if (null != loader && loader.getClass() != DatadogClassLoader.class) {
        ClassDefining.begin(loader, bytecode, offset, length);
      }
    }
  }
}
