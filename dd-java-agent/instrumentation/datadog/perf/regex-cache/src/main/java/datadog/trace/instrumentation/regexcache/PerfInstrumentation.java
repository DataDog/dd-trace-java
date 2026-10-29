package datadog.trace.instrumentation.regexcache;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.bytebuddy.csi.Advices;
import datadog.trace.agent.tooling.bytebuddy.csi.CallSiteInstrumentation;
import datadog.trace.agent.tooling.bytebuddy.csi.CallSiteSupplier;
import datadog.trace.agent.tooling.csi.CallSites;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.api.ProductActivation;
import datadog.trace.api.perf.PerfCallSites;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public class PerfInstrumentation extends CallSiteInstrumentation {

  private static final String[] EXCLUDED_PREFIXES = {
    "java.", "javax.", "jdk.", "sun.", "com.sun.", "datadog.", "com.datadog.", "com.datadoghq."
  };

  public static boolean isExcluded(final String className) {
    for (final String prefix : EXCLUDED_PREFIXES) {
      if (className.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }

  public PerfInstrumentation() {
    super("regex-cache");
  }

  @Override
  public ElementMatcher<TypeDescription> callerType() {
    return CallerTypeMatcher.INSTANCE;
  }

  @Override
  public boolean isApplicable(final Set<TargetSystem> enabledSystems) {
    return enabledSystems.contains(TargetSystem.TRACING);
  }

  @Override
  public boolean isEnabled() {
    final InstrumenterConfig config = InstrumenterConfig.get();
    return super.isEnabled()
        && config.isRegexCacheEnabled()
        && config.getIastActivation() != ProductActivation.FULLY_ENABLED;
  }

  @Override
  protected CallSiteSupplier callSites() {
    return PerfCallSiteSupplier.INSTANCE;
  }

  @Override
  protected Advices buildAdvices(final Iterable<CallSites> callSites) {
    RegexCacheStatsReporter.start();
    return super.buildAdvices(callSites);
  }

  public static final class CallerTypeMatcher
      extends ElementMatcher.Junction.ForNonNullValues<TypeDescription> {

    public static final CallerTypeMatcher INSTANCE = new CallerTypeMatcher();

    @Override
    protected boolean doMatch(final TypeDescription target) {
      return !isExcluded(target.getName());
    }
  }

  public static final class PerfCallSiteSupplier implements CallSiteSupplier {

    public static final CallSiteSupplier INSTANCE = new PerfCallSiteSupplier();

    @Override
    public Iterable<CallSites> get() {
      final List<CallSites> result = new ArrayList<>();
      final ClassLoader loader = PerfInstrumentation.class.getClassLoader();
      for (final PerfCallSites callSites : ServiceLoader.load(PerfCallSites.class, loader)) {
        result.add((CallSites) callSites);
      }
      return result;
    }
  }
}
