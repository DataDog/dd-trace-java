package datadog.trace.agent.tooling;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.ANY_CLASS_LOADER;
import static java.util.Collections.addAll;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static net.bytebuddy.matcher.ElementMatchers.isSynthetic;

import datadog.trace.agent.tooling.iast.IastPostProcessorFactory;
import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.agent.tooling.muzzle.ReferenceMatcher;
import datadog.trace.agent.tooling.muzzle.ReferenceProvider;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.api.ProductActivation;
import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import datadog.trace.util.Strings;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class InstrumenterModule implements Instrumenter {

  /**
   * Since several systems share the same instrumentation infrastructure in order to enable only the
   * applicable {@link Instrumenter instrumenters} on startup each {@linkplain InstrumenterModule}
   * must declare its target system. The systems currently supported include:
   *
   * <ul>
   *   <li>{@link TargetSystem#TRACING tracing}
   *   <li>{@link TargetSystem#PROFILING profiling}
   *   <li>{@link TargetSystem#APPSEC appsec}
   *   <li>{@link TargetSystem#IAST iast}
   *   <li>{@link TargetSystem#CIVISIBILITY ci-visibility}
   *   <li>{@link TargetSystem#USM usm}
   * </ul>
   */
  public enum TargetSystem {
    TRACING,
    PROFILING,
    APPSEC,
    IAST,
    CIVISIBILITY,
    USM,
    LLMOBS,
    CONTEXT_TRACKING,
  }

  private static final Logger log = LoggerFactory.getLogger(InstrumenterModule.class);

  protected static final String[] NO_HELPERS = {};

  private final List<String> instrumentationNames;
  private final String instrumentationPrimaryName;
  private final boolean enabled;

  protected final String packageName = Strings.getPackageName(getClass().getName());

  public InstrumenterModule(final String instrumentationName, final String... additionalNames) {
    instrumentationNames = new ArrayList<>(1 + additionalNames.length);
    instrumentationNames.add(instrumentationName);
    addAll(instrumentationNames, additionalNames);
    instrumentationPrimaryName = instrumentationName;

    enabled = InstrumenterConfig.get().isIntegrationEnabled(instrumentationNames, defaultEnabled());
  }

  public String name() {
    return instrumentationPrimaryName;
  }

  public Iterable<String> names() {
    return instrumentationNames;
  }

  /** Modules with higher order values are applied <i>after</i> those with lower values. */
  public int order() {
    return 0;
  }

  public List<Instrumenter> typeInstrumentations() {
    return singletonList(this);
  }

  public final ReferenceMatcher getInstrumentationMuzzle() {
    return loadStaticMuzzleReferences(getClass().getClassLoader(), getClass().getName())
        .withReferenceProvider(runtimeMuzzleReferences());
  }

  public static ReferenceMatcher loadStaticMuzzleReferences(
      ClassLoader classLoader, String instrumentationClass) {
    String muzzleClass = instrumentationClass + "$Muzzle";
    try {
      // Muzzle class contains static references captured at build-time
      // see datadog.trace.agent.tooling.muzzle.MuzzleGenerator
      return (ReferenceMatcher) classLoader.loadClass(muzzleClass).getMethod("create").invoke(null);
    } catch (Throwable e) {
      log.warn("Failed to load - muzzle.class={}", muzzleClass, e);
      return ReferenceMatcher.NO_REFERENCES;
    }
  }

  /**
   * @return Class names of helpers to inject into the user's classloader
   */
  public String[] helperClassNames() {
    return NO_HELPERS;
  }

  /**
   * @return {@code true} if helper classes should be injected with the agent's {@link CodeSource}
   */
  public boolean useAgentCodeSource() {
    return false;
  }

  /** Override this to automatically inject all (non-bootstrap) helper dependencies. */
  public boolean injectHelperDependencies() {
    return false;
  }

  /** Classes that the muzzle plugin assumes will be injected */
  public String[] muzzleIgnoredClassNames() {
    return helperClassNames();
  }

  /** Override this to supply additional Muzzle references at build time. */
  public Reference[] additionalMuzzleReferences() {
    return null;
  }

  /** Override this to supply additional Muzzle references during startup. */
  public ReferenceProvider runtimeMuzzleReferences() {
    return null;
  }

  /** Override this to validate against a specific named MuzzleDirective. */
  public String muzzleDirective() {
    return null;
  }

  /** Override this to supply additional class-loader requirements. */
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
    return ANY_CLASS_LOADER;
  }

  /**
   * @return A type matcher used to ignore some methods when applying transformation.
   */
  public ElementMatcher<? super MethodDescription> methodIgnoreMatcher() {
    // By default ByteBuddy will skip all methods that are synthetic at the top level, but since
    // we need to instrument some synthetic methods in Scala and changed that, we make the default
    // here to ignore synthetic methods to not change the behavior for unaware instrumentations
    return isSynthetic();
  }

  /** Override this to apply shading to method advice and injected helpers. */
  public Map<String, String> adviceShading() {
    return null;
  }

  /** Override this to post-process the operand stack of any transformed methods. */
  public Advice.PostProcessor.Factory postProcessor() {
    return null;
  }

  /**
   * Context stores to define for this instrumentation. Are added to matching class loaders.
   *
   * <p>A map of {class-name -> context-class-name}. Keys (and their subclasses) will be associated
   * with a context of the value.
   */
  public Map<String, String> contextStore() {
    return emptyMap();
  }

  protected boolean defaultEnabled() {
    return InstrumenterConfig.get().isIntegrationsEnabled();
  }

  public boolean isEnabled() {
    return enabled;
  }

  /**
   * Indicates the applicability of an {@linkplain InstrumenterModule} to the given system.<br>
   *
   * @param enabledSystems a set of all the enabled target systems
   * @return {@literal true} if the set of enabled systems contains all the ones required by this
   *     particular {@linkplain InstrumenterModule}
   */
  public boolean isApplicable(Set<TargetSystem> enabledSystems) {
    return false;
  }

  protected final boolean isShortcutMatchingEnabled(boolean defaultToShortcut) {
    return InstrumenterConfig.get()
        .isIntegrationShortcutMatchingEnabled(singletonList(name()), defaultToShortcut);
  }

  /** Parent class for all tracing related instrumentations */
  public abstract static class Tracing extends InstrumenterModule {
    public Tracing(String instrumentationName, String... additionalNames) {
      super(instrumentationName, additionalNames);
    }

    @Override
    public boolean isApplicable(Set<TargetSystem> enabledSystems) {
      return enabledSystems.contains(TargetSystem.TRACING);
    }
  }

  /** Parent class for all profiling related instrumentations */
  public abstract static class Profiling extends InstrumenterModule {
    public Profiling(String instrumentationName, String... additionalNames) {
      super(instrumentationName, additionalNames);
    }

    @Override
    public boolean isApplicable(Set<TargetSystem> enabledSystems) {
      return enabledSystems.contains(TargetSystem.PROFILING);
    }

    @Override
    public boolean isEnabled() {
      return super.isEnabled()
          && !ConfigProvider.getInstance()
              .getBoolean(ProfilingConfig.PROFILING_ULTRA_MINIMAL, false);
    }
  }

  /** Parent class for all AppSec related instrumentations */
  public abstract static class AppSec extends InstrumenterModule {
    public AppSec(String instrumentationName, String... additionalNames) {
      super(instrumentationName, additionalNames);
    }

    @Override
    public boolean isApplicable(Set<TargetSystem> enabledSystems) {
      return enabledSystems.contains(TargetSystem.APPSEC);
    }
  }

  /** Parent class for all IAST related instrumentations */
  @SuppressForbidden
  public abstract static class Iast extends InstrumenterModule {
    public Iast(String instrumentationName, String... additionalNames) {
      super(instrumentationName, additionalNames);
    }

    @Override
    public List<Instrumenter> typeInstrumentations() {
      preloadClassNames();
      return super.typeInstrumentations();
    }

    @Override
    public boolean isApplicable(Set<TargetSystem> enabledSystems) {
      if (enabledSystems.contains(TargetSystem.IAST)) {
        return true;
      }
      final InstrumenterConfig cfg = InstrumenterConfig.get();
      if (!isOptOutEnabled() || cfg.isIastFullyDisabled()) {
        return false;
      }
      return cfg.getAppSecActivation() == ProductActivation.FULLY_ENABLED;
    }

    /**
     * Force loading of classes that need to be instrumented, but are using during instrumentation.
     */
    private void preloadClassNames() {
      String[] list = getClassNamesToBePreloaded();
      if (list != null) {
        for (String clazz : list) {
          try {
            Class.forName(clazz);
          } catch (Throwable t) {
            log.debug("Error force loading {} class", clazz);
          }
        }
      }
    }

    /** Get classes to force load* */
    public String[] getClassNamesToBePreloaded() {
      return null;
    }

    @Override
    public Advice.PostProcessor.Factory postProcessor() {
      return IastPostProcessorFactory.INSTANCE;
    }

    protected boolean isOptOutEnabled() {
      return false;
    }
  }

  /** Parent class for all USM related instrumentations */
  public abstract static class Usm extends InstrumenterModule {
    public Usm(String instrumentationName, String... additionalNames) {
      super(instrumentationName, additionalNames);
    }

    @Override
    public boolean isApplicable(Set<TargetSystem> enabledSystems) {
      return enabledSystems.contains(TargetSystem.USM);
    }
  }

  /** Parent class for all CI related instrumentations */
  public abstract static class CiVisibility extends InstrumenterModule {
    public CiVisibility(String instrumentationName, String... additionalNames) {
      super(instrumentationName, additionalNames);
    }

    @Override
    public boolean isApplicable(Set<TargetSystem> enabledSystems) {
      return enabledSystems.contains(TargetSystem.CIVISIBILITY);
    }
  }

  /** Parent class for all the context tracking instrumentations */
  public abstract static class ContextTracking extends InstrumenterModule {
    public ContextTracking(String instrumentationName, String... additionalNames) {
      super(instrumentationName, additionalNames);
    }

    @Override
    public boolean isApplicable(Set<TargetSystem> enabledSystems) {
      return enabledSystems.contains(TargetSystem.CONTEXT_TRACKING);
    }
  }
}
