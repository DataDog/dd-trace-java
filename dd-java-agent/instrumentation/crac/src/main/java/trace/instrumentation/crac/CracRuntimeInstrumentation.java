package trace.instrumentation.crac;

import static net.bytebuddy.matcher.ElementMatchers.isTypeInitializer;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Platform;
import net.bytebuddy.asm.Advice;
import org.crac.Core;

@AutoService(InstrumenterModule.class)
public final class CracRuntimeInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType {

  public CracRuntimeInstrumentation() {
    super("crac");
  }

  @Override
  protected boolean defaultEnabled() {
    return !Platform.isNativeImageBuilder();
  }

  @Override
  public String instrumentedType() {
    return "org.crac.Core";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".TracerResource"};
  }

  @Override
  public void methodAdvice(Instrumenter.MethodTransformer transformer) {
    transformer.applyAdvice(
        isTypeInitializer(), CracRuntimeInstrumentation.class.getName() + "$TracerResourceAdvice");
  }

  public static class TracerResourceAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit() {
      Core.getGlobalContext().register(new TracerResource());
    }
  }
}
