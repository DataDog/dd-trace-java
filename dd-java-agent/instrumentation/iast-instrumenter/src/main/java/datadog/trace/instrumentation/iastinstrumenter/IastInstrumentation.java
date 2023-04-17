package datadog.trace.instrumentation.iastinstrumenter;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.bytebuddy.csi.CallSiteInstrumentation;
import datadog.trace.agent.tooling.bytebuddy.csi.CallSiteSupplier;
import datadog.trace.agent.tooling.csi.CallSiteAdvice;
import datadog.trace.api.iast.IastAdvice;
import datadog.trace.api.iast.telemetry.Verbosity;
import datadog.trace.instrumentation.iastinstrumenter.telemetry.TelemetryCallSiteSupplier;
import java.util.ServiceLoader;
import java.util.Set;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class IastInstrumentation extends CallSiteInstrumentation {

  public IastInstrumentation() {
    super("IastInstrumentation");
  }

  @Override
  public ElementMatcher<TypeDescription> callerType() {
    return IastMatcher.INSTANCE;
  }

  @Override
  public boolean isApplicable(final Set<TargetSystem> enabledSystems) {
    return enabledSystems.contains(TargetSystem.IAST);
  }

  @Override
  protected CallSiteSupplier callSites() {
    return IastCallSiteSupplier.INSTANCE;
  }

  public static final class IastMatcher
      extends ElementMatcher.Junction.ForNonNullValues<TypeDescription> {
    public static final IastMatcher INSTANCE = new IastMatcher();

    @Override
    protected boolean doMatch(TypeDescription target) {
      return IastExclusionTrie.apply(target.getName()) != 1;
    }
  }

  public static class IastCallSiteSupplier implements CallSiteSupplier {

    public static final CallSiteSupplier INSTANCE;

    static {
      CallSiteSupplier supplier = new IastCallSiteSupplier(IastAdvice.class);
      final Verbosity verbosity = Verbosity.getLevel();
      if (verbosity != Verbosity.OFF) {
        supplier = new TelemetryCallSiteSupplier(verbosity, supplier);
      }
      INSTANCE = supplier;
    }

    private final Class<?> spiInterface;

    public IastCallSiteSupplier(final Class<?> spiInterface) {
      this.spiInterface = spiInterface;
    }

    @SuppressWarnings("unchecked")
    @Override
    public Iterable<CallSiteAdvice> get() {
      final ClassLoader targetClassLoader = CallSiteInstrumentation.class.getClassLoader();
      return (ServiceLoader<CallSiteAdvice>) ServiceLoader.load(spiInterface, targetClassLoader);
    }
  }
}
