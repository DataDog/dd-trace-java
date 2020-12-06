package datadog.trace.agent.tooling.context;

import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.safeHasSuperType;
import static datadog.trace.agent.tooling.context.ContextStoreUtils.unpackContextStore;
import static net.bytebuddy.matcher.ElementMatchers.named;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.Instrumenter.Default;
import datadog.trace.api.Config;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * InstrumentationContextProvider which stores context in a field that is injected into a class and
 * falls back to global map if field was not injected.
 *
 * <p>This is accomplished by
 *
 * <ol>
 *   <li>Injecting a Dynamic Interface that provides getter and setter for context field
 *   <li>Applying Dynamic Interface to a type needing context, implementing interface methods and
 *       adding context storage field
 *   <li>Injecting a Dynamic Class created from {@link ContextStoreImplementationTemplate} to use
 *       injected field or fall back to a static map
 *   <li>Rewritting calls to the context-store to access the specific dynamic {@link
 *       ContextStoreImplementationTemplate}
 * </ol>
 *
 * <p>Example:<br>
 * <em>InstrumentationContext.get(Runnable.class, RunnableState.class)")</em><br>
 * is rewritten to:<br>
 * <em>FieldBackedProvider$ContextStore$Runnable$RunnableState12345.getContextStore(runnableRunnable.class,
 * RunnableState.class)</em>
 *
 * @deprecated not used in the new field-injection strategy
 */
@Deprecated
@Slf4j
public final class FieldBackedProvider implements InstrumentationContextProvider {

  /*
   * HashMap from the instrumentations contextClassLoaderMatcher to a set of pairs (context holder, context class)
   * for which we have matchers installed. We use this to make sure we do not install matchers repeatedly for cases
   * when same context class is used by multiple instrumentations.
   */
  private static final HashMap<ElementMatcher<ClassLoader>, Set<Map.Entry<String, String>>>
      INSTALLED_CONTEXT_MATCHERS = new HashMap<>();

  private static final Set<Map.Entry<String, String>> INSTALLED_HELPERS = new HashSet<>();

  private final String instrumenterName;
  private final Map<ElementMatcher<ClassLoader>, Map<String, String>> matchedContextStores;
  private final ContextStoreInjector contextStoreInjector;
  private final FieldInjector fieldInjector;
  private final boolean fieldInjectionEnabled;

  public FieldBackedProvider(
      final Instrumenter.Default instrumenter,
      Map<ElementMatcher<ClassLoader>, Map<String, String>> matchedContextStores) {
    Map<String, String> contextStore = unpackContextStore(matchedContextStores);
    ByteBuddy byteBuddy = new ByteBuddy();
    this.instrumenterName = instrumenter.getClass().getName();
    this.matchedContextStores = matchedContextStores;
    this.fieldInjector = new FieldInjector(contextStore, byteBuddy);
    this.contextStoreInjector =
        new ContextStoreInjector(contextStore, byteBuddy, fieldInjector, instrumenterName);
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
      builder = builder.transform(contextStoreInjector.readTransformer());
      builder = contextStoreInjector.injectIntoBootstrapClassloader(builder);
      builder = fieldInjector.injectIntoBootstrapClassloader(builder);
    }
    return builder;
  }

  /** Clear set that prevents multiple matchers for same context class */
  public static void resetContextMatchers() {
    synchronized (INSTALLED_CONTEXT_MATCHERS) {
      INSTALLED_CONTEXT_MATCHERS.clear();
      INSTALLED_HELPERS.clear();
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
            if (!installedContextMatchers.add(entry)) {
              if (log.isDebugEnabled()) {
                log.debug(
                    "Skipping duplicate builder in {} for matcher {}: {} -> {}",
                    instrumenterName,
                    classLoaderMatcher,
                    entry.getKey(),
                    entry.getValue());
              }
              continue;
            }

            /*
             * For each context store defined in a current instrumentation we create an agent builder
             * that injects necessary fields.
             */
            builder =
                builder
                    .type(safeHasSuperType(named(entry.getKey())), classLoaderMatcher)
                    .and(ShouldInjectFieldsMatcher.of(entry.getKey(), entry.getValue()))
                    .and(Default.NOT_DECORATOR_MATCHER)
                    .transform(
                        fieldInjector.fieldAccessTransformer(entry.getKey(), entry.getValue()));
          }

          synchronized (INSTALLED_HELPERS) {
            if (!INSTALLED_HELPERS.contains(entry)) {
              /*
               * We inject helpers here as well as when instrumentation is applied to ensure that
               * helpers are present even if instrumented classes are not loaded, but classes with state
               * fields added are loaded (e.g. sun.net.www.protocol.https.HttpsURLConnectionImpl).
               */
              builder = fieldInjector.injectIntoBootstrapClassloader(builder);
              builder = contextStoreInjector.injectIntoBootstrapClassloader(builder);
              INSTALLED_HELPERS.add(entry);
            }
          }
        }
      }
    }
    return builder;
  }
}
