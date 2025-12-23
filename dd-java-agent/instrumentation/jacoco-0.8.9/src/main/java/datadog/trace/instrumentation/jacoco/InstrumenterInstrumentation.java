package datadog.trace.instrumentation.jacoco;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.nameEndsWith;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.Set;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public class InstrumenterInstrumentation extends InstrumenterModule.CiVisibility
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {
  public InstrumenterInstrumentation() {
    super("jacoco");
  }

  @Override
  public boolean isApplicable(Set<TargetSystem> enabledSystems) {
    return super.isApplicable(enabledSystems);
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.jacoco.agent.rt.IAgent";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    // The jacoco javaagent jar that is published relocates internal classes to an "obfuscated"
    // package name ex. org.jacoco.agent.rt.internal_72ddf3b.core.instr.Instrumenter
    return nameStartsWith("org.jacoco.agent.rt.internal")
        .and(nameEndsWith(".core.instr.Instrumenter"));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".CoverageDataInjector",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("instrument")).and(takesArguments(byte[].class)),
        getClass().getName() + "$InstrumentAdvice");
  }

  public static class InstrumentAdvice {
    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    static byte[] enter(@Advice.Argument(0) byte[] bytes) {
      // Jacoco initialization runs inside preMain of their agent which,
      // depending on how a specific project is set up,
      // can happen either after or _before_ our preMain.

      // It means we cannot hook into Jacoco's init methods:
      // even if we redefine them it will not help,
      // as it is possible that they have already been executed
      // by the time we do it.

      // Therefore, we have to repeatedly call this "init" method here:
      // when it is called the first time its class will be initialized,
      // and our "should-run-once" logic will be triggered
      CoverageDataInjector.init();

      // we cannot insert our custom Jacoco probes into classes compiled with Java older than 1.5
      // because there is no support for pushing types onto the stack
      int majorVersion = ((bytes[6] & 0xFF) << 8) | (bytes[7] & 0xFF);
      if (majorVersion < 49) {
        return bytes; // skip class instrumentation
      } else {
        return null; // go ahead and instrument
      }
    }
  }
}
