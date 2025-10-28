package datadog.trace.instrumentation.iastinstrumenter;

import static net.bytebuddy.matcher.ElementMatchers.not;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.bytebuddy.csi.Advices;
import datadog.trace.agent.tooling.bytebuddy.csi.Advices.Listener;
import datadog.trace.agent.tooling.bytebuddy.csi.CallSiteInstrumentation;
import datadog.trace.agent.tooling.bytebuddy.csi.CallSiteSupplier;
import datadog.trace.agent.tooling.csi.CallSites;
import datadog.trace.api.Config;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.api.ProductActivation;
import datadog.trace.api.appsec.RaspCallSites;
import datadog.trace.api.iast.IastCallSites;
import datadog.trace.api.iast.telemetry.Verbosity;
import datadog.trace.instrumentation.iastinstrumenter.service.CallSitesLoader;
import datadog.trace.instrumentation.iastinstrumenter.telemetry.TelemetryCallSiteSupplier;
import java.util.LinkedList;
import java.util.List;
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
    return enabledSystems.contains(TargetSystem.IAST) || isRaspEnabled();
  }

  private boolean isRaspEnabled() {
    return InstrumenterConfig.get().getAppSecActivation() == ProductActivation.FULLY_ENABLED
        && Config.get().isAppSecRaspEnabled();
  }

  @Override
  protected CallSiteSupplier callSites() {
    return IastCallSiteSupplier.INSTANCE;
  }

  @Override
  protected Advices buildAdvices(final Iterable<CallSites> callSites) {
    final List<Listener> listeners = new LinkedList<>();
    final boolean iastActive =
        InstrumenterConfig.get().getIastActivation() == ProductActivation.FULLY_ENABLED;
    if (iastActive) {
      if (Config.get().isIastHardcodedSecretEnabled()) {
        listeners.add(IastHardcodedSecretListener.INSTANCE);
      }
      listeners.add(StratumListener.INSTANCE);
    }
    return Advices.fromCallSites(callSites, listeners.toArray(new Listener[0]));
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

    // this deliberately only considers anonymous types following the Java naming convention
    private static final ElementMatcher.Junction<TypeDescription> ANONYMOUS_TYPE_MATCHER =
        new ElementMatcher.Junction.ForNonNullValues<TypeDescription>() {
          @Override
          protected boolean doMatch(TypeDescription target) {
            String name = target.getName();
            // search the name in reverse until we find a $ or non-digit
            for (int end = name.length() - 1, i = end; i > 0; i--) {
              char c = name.charAt(i);
              if (c == '$' && i < end) {
                return true; // only seen digits so far, assume anonymous
              } else if (c < '0' || c > '9') {
                break; // non-digit character found, assume not anonymous
              }
            }
            return false;
          }
        };

    static {
      if (Config.get().isIastAnonymousClassesEnabled()) {
        INSTANCE = TRIE_MATCHER;
      } else {
        INSTANCE = TRIE_MATCHER.and(not(ANONYMOUS_TYPE_MATCHER));
      }
    }
  }

  public static class IastCallSiteSupplier implements CallSiteSupplier {

    public static final CallSiteSupplier INSTANCE;

    static {
      final List<Class<?>> spi = new LinkedList<>();
      final boolean iastActive =
          InstrumenterConfig.get().getIastActivation() == ProductActivation.FULLY_ENABLED;
      if (iastActive) {
        spi.add(IastCallSites.class);
      }
      if (Config.get().isAppSecRaspEnabled()) {
        spi.add(RaspCallSites.class);
      }
      CallSiteSupplier supplier = new IastCallSiteSupplier(spi.toArray(new Class[0]));
      final Verbosity verbosity = Config.get().getIastTelemetryVerbosity();
      if (iastActive && verbosity != Verbosity.OFF) {
        supplier = new TelemetryCallSiteSupplier(verbosity, supplier);
      }
      INSTANCE = supplier;
    }

    private final Class<?>[] spiInterfaces;

    public IastCallSiteSupplier(final Class<?>... spiInterfaces) {
      this.spiInterfaces = spiInterfaces;
    }

    @Override
    public Iterable<CallSites> get() {
      final ClassLoader targetClassLoader = CallSiteInstrumentation.class.getClassLoader();
      return CallSitesLoader.load(targetClassLoader, spiInterfaces);
    }
  }
}
