package datadog.trace.instrumentation.servicetalk0_42_56;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.servicetalk.concurrent.api.CapturedContextProvider;
import java.util.ArrayList;
import java.util.List;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class CapturedContextProvidersInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public CapturedContextProvidersInstrumentation() {
    super("servicetalk", "servicetalk-concurrent");
  }

  @Override
  public String instrumentedType() {
    return "io.servicetalk.concurrent.api.CapturedContextProviders";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(named("loadProviders"), getClass().getName() + "$LoadProvidersAdvice");
  }

  private static final class LoadProvidersAdvice {
    @SuppressFBWarnings("UC_USELESS_OBJECT")
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void exit(@Advice.Return(readOnly = false) java.util.List loadedProviders) {
      List<CapturedContextProvider> providers = new ArrayList<>(loadedProviders.size() + 1);
      providers.addAll(loadedProviders);
      providers.add(new DatadogCapturedContextProvider());
      loadedProviders = providers;
    }
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".DatadogCapturedContextProvider",
      packageName + ".DatadogCapturedContextProvider$WithDatadogCapturedContext",
    };
  }
}
