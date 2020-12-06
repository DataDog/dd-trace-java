package datadog.trace.agent.tooling.context;

import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.safeHasSuperType;
import static datadog.trace.agent.tooling.context.ContextStoreUtils.unpackContextStore;
import static datadog.trace.agent.tooling.context.ContextStoreUtils.wrapVisitor;
import static net.bytebuddy.matcher.ElementMatchers.named;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.Instrumenter.Default;
import datadog.trace.api.Config;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * InstrumentationContextProvider which stores context in a field that is injected into a class and
 * falls back to tracking the context association in a global weak-map if the field wasn't injected.
 *
 * <p>This is accomplished by
 *
 * <ol>
 *   <li>Rewriting calls to {@link InstrumentationContext} to access stores based on numeric ids
 *   <li>Injecting fields in the earliest holder class that matches the context key
 *   <li>Injecting a getter and setter that retrieves context stored in the injected fields
 *   <li>Delegating to the superclass getter and setter if a superclass is also a context holder
 *   <li>Delegating to weak-map if neither this class or superclass have a field for the context
 * </ol>
 */
@Slf4j
public final class FieldBackedContextProvider implements InstrumentationContextProvider {

  /*
   * Mapping from the instrumentations contextClassLoaderMatcher to a set of pairs (context holder, context class)
   * for which we have matchers installed. We use this to make sure we do not install matchers repeatedly for cases
   * when same context class is used by multiple instrumentations.
   */
  private static final HashMap<ElementMatcher<ClassLoader>, Set<Map.Entry<String, String>>>
      INSTALLED_CONTEXT_MATCHERS = new HashMap<>();

  private final String instrumenterName;
  private final Map<ElementMatcher<ClassLoader>, Map<String, String>> matchedContextStores;
  private final Map<String, String> contextStore;
  private final boolean fieldInjectionEnabled;

  public FieldBackedContextProvider(
      final Instrumenter.Default instrumenter,
      final Map<ElementMatcher<ClassLoader>, Map<String, String>> matchedContextStores) {
    this.instrumenterName = instrumenter.getClass().getName();
    this.matchedContextStores = matchedContextStores;
    this.contextStore = unpackContextStore(matchedContextStores);
    this.fieldInjectionEnabled = Config.get().isRuntimeContextFieldInjection();
  }

  @Override
  public AgentBuilder.Identified.Extendable instrumentationTransformer(
      AgentBuilder.Identified.Extendable builder) {
    if (!matchedContextStores.isEmpty()) {
      /*
       * Install transformer that rewrites accesses to context store with specialized bytecode that
       * invokes appropriate storage implementation.
       */
      builder =
          builder.transform(
              wrapVisitor(new FieldBackedContextRequestRewriter(contextStore, instrumenterName)));
    }
    return builder;
  }

  /** Clear set that prevents multiple matchers for same context class */
  public static void resetContextMatchers() {
    synchronized (INSTALLED_CONTEXT_MATCHERS) {
      INSTALLED_CONTEXT_MATCHERS.clear();
    }
  }

  @Override
  public AgentBuilder.Identified.Extendable additionalInstrumentation(
      AgentBuilder.Identified.Extendable builder) {

    if (fieldInjectionEnabled) {
      for (Map.Entry<ElementMatcher<ClassLoader>, Map<String, String>> matcherAndStores :
          matchedContextStores.entrySet()) {
        ElementMatcher<ClassLoader> classLoaderMatcher = matcherAndStores.getKey();
        for (Map.Entry<String, String> entry : matcherAndStores.getValue().entrySet()) {
          /*
           * For each context store defined in a current instrumentation we create an agent builder
           * that injects necessary fields.
           * Note: this synchronization should not have any impact on performance
           * since this is done when agent builder is being made, it doesn't affect actual
           * class transformation.
           */
          Set<Map.Entry<String, String>> installedContextMatchers;
          synchronized (INSTALLED_CONTEXT_MATCHERS) {
            installedContextMatchers = INSTALLED_CONTEXT_MATCHERS.get(classLoaderMatcher);
            if (installedContextMatchers == null) {
              installedContextMatchers = new HashSet<>();
              INSTALLED_CONTEXT_MATCHERS.put(classLoaderMatcher, installedContextMatchers);
            }
          }
          synchronized (installedContextMatchers) {
            final String keyClassName = entry.getKey();
            final String contextClassName = entry.getValue();

            if (!installedContextMatchers.add(entry)) {
              if (log.isDebugEnabled()) {
                log.debug(
                    "Skipping duplicate builder in {} for matcher {}: {} -> {}",
                    instrumenterName,
                    classLoaderMatcher,
                    keyClassName,
                    contextClassName);
              }
              continue;
            }

            /*
             * For each context store defined in a current instrumentation we create an agent builder
             * that injects necessary fields.
             */
            builder =
                builder
                    .type(safeHasSuperType(named(keyClassName)), classLoaderMatcher)
                    .and(ShouldInjectFieldsMatcher.of(keyClassName, contextClassName))
                    .and(Default.NOT_DECORATOR_MATCHER)
                    .transform(
                        wrapVisitor(
                            new FieldBackedContextInjector(keyClassName, contextClassName)));
          }
        }
      }
    }
    return builder;
  }
}
