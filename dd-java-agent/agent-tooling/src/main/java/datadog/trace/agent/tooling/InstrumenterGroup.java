package datadog.trace.agent.tooling;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.ANY_CLASS_LOADER;
import static java.util.Collections.addAll;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static net.bytebuddy.matcher.ElementMatchers.isSynthetic;

import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.agent.tooling.muzzle.ReferenceMatcher;
import datadog.trace.agent.tooling.muzzle.ReferenceProvider;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.util.Strings;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class InstrumenterGroup implements Instrumenter {
  private static final Logger log = LoggerFactory.getLogger(InstrumenterGroup.class);

  private final int instrumentationId;
  private final List<String> instrumentationNames;
  private final String instrumentationPrimaryName;
  private final boolean enabled;

  protected final String packageName = Strings.getPackageName(getClass().getName());

  public InstrumenterGroup(final String instrumentationName, final String... additionalNames) {
    instrumentationId = Instrumenters.currentInstrumentationId();
    instrumentationNames = new ArrayList<>(1 + additionalNames.length);
    instrumentationNames.add(instrumentationName);
    addAll(instrumentationNames, additionalNames);
    instrumentationPrimaryName = instrumentationName;

    enabled = InstrumenterConfig.get().isIntegrationEnabled(instrumentationNames, defaultEnabled());
  }

  public int instrumentationId() {
    return instrumentationId;
  }

  public String name() {
    return instrumentationPrimaryName;
  }

  public Iterable<String> names() {
    return instrumentationNames;
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

  /** @return Class names of helpers to inject into the user's classloader */
  public String[] helperClassNames() {
    return new String[0];
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
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    return ANY_CLASS_LOADER;
  }

  /** @return A type matcher used to ignore some methods when applying transformation. */
  public ElementMatcher<? super MethodDescription> methodIgnoreMatcher() {
    // By default ByteBuddy will skip all methods that are synthetic at the top level, but since
    // we need to instrument some synthetic methods in Scala and changed that, we make the default
    // here to ignore synthetic methods to not change the behavior for unaware instrumentations
    return isSynthetic();
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
   * Indicates the applicability of an {@linkplain InstrumenterGroup} to the given system.<br>
   *
   * @param enabledSystems a set of all the enabled target systems
   * @return {@literal true} if the set of enabled systems contains all the ones required by this
   *     particular {@linkplain InstrumenterGroup}
   */
  public boolean isApplicable(Set<TargetSystem> enabledSystems) {
    return false;
  }

  protected final boolean isShortcutMatchingEnabled(boolean defaultToShortcut) {
    return InstrumenterConfig.get()
        .isIntegrationShortcutMatchingEnabled(singletonList(name()), defaultToShortcut);
  }
}
