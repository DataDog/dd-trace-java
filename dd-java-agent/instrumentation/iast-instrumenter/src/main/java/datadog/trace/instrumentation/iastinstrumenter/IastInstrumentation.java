package datadog.trace.instrumentation.iastinstrumenter;

import static net.bytebuddy.matcher.ElementMatchers.not;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.bytebuddy.csi.Advices;
import datadog.trace.agent.tooling.bytebuddy.csi.CallSiteInstrumentation;
import datadog.trace.agent.tooling.bytebuddy.csi.CallSiteSupplier;
import datadog.trace.agent.tooling.csi.CallSites;
import datadog.trace.api.Config;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.api.ProductActivation;
import datadog.trace.api.iast.IastCallSites;
import datadog.trace.api.iast.telemetry.Verbosity;
import datadog.trace.instrumentation.iastinstrumenter.telemetry.TelemetryCallSiteSupplier;
import java.util.ServiceLoader;
import java.util.Set;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public class IastInstrumentation extends CallSiteInstrumentation {

  public IastInstrumentation() {
    super("IastInstrumentation");
  }

  @Override
  public ElementMatcher<TypeDescription> callerType() {
    return IastMatchers.INSTANCE;
  }

  @Override
  public boolean isApplicable(final Set<TargetSystem> enabledSystems) {
    return enabledSystems.contains(TargetSystem.IAST)
        || (isOptOutEnabled()
            && InstrumenterConfig.get().getAppSecActivation() == ProductActivation.FULLY_ENABLED);
  }

  @Override
  protected CallSiteSupplier callSites() {
    return IastCallSiteSupplier.INSTANCE;
  }

  @Override
  protected Advices buildAdvices(final Iterable<CallSites> callSites) {
    if (Config.get().isIastHardcodedSecretEnabled()) {
      return Advices.fromCallSites(callSites, IastHardcodedSecretListener.INSTANCE);
    } else {
      return Advices.fromCallSites(callSites);
    }
  }

  protected boolean isOptOutEnabled() {
    return false;
  }

  public static final class IastMatchers {

    public static final ElementMatcher<TypeDescription> INSTANCE;

    private static final ElementMatcher.Junction<TypeDescription> TRIE_MATCHER =
        new ElementMatcher.Junction.ForNonNullValues<TypeDescription>() {
          @Override
          protected boolean doMatch(TypeDescription target) {
            return IastExclusionTrie.apply(target.getName()) != 1;
          }
        };

    static {
      if (Config.get().isIastAnonymousClassesEnabled()) {
        INSTANCE = TRIE_MATCHER;
      } else {
        INSTANCE = TRIE_MATCHER.and(not(TypeDescription::isAnonymousType));
      }
    }
  }

  public static class IastCallSiteSupplier implements CallSiteSupplier {

    public static final CallSiteSupplier INSTANCE;

    static {
      CallSiteSupplier supplier = new IastCallSiteSupplier(IastCallSites.class);
      final Config config = Config.get();
      final Verbosity verbosity = config.getIastTelemetryVerbosity();
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
    public Iterable<CallSites> get() {
      final ClassLoader targetClassLoader = CallSiteInstrumentation.class.getClassLoader();
      return (ServiceLoader<CallSites>) ServiceLoader.load(spiInterface, targetClassLoader);
    }
  }
}
