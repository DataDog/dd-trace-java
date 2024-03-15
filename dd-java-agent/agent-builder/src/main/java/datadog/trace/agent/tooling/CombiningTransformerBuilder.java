package datadog.trace.agent.tooling;

import static datadog.trace.agent.tooling.bytebuddy.DDTransformers.defaultTransformers;
import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.ANY_CLASS_LOADER;
import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamedOneOf;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.declaresAnnotation;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.not;

import datadog.trace.agent.tooling.Instrumenter.WithPostProcessor;
import datadog.trace.agent.tooling.bytebuddy.ExceptionHandlers;
import datadog.trace.agent.tooling.context.FieldBackedContextInjector;
import datadog.trace.agent.tooling.context.FieldBackedContextMatcher;
import datadog.trace.agent.tooling.context.FieldBackedContextRequestRewriter;
import datadog.trace.agent.tooling.muzzle.MuzzleCheck;
import datadog.trace.api.InstrumenterConfig;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.utility.JavaModule;

/** Builds multiple instrumentations into a single combining-matcher and splitting-transformer. */
public final class CombiningTransformerBuilder
    implements Instrumenter.TypeTransformer, Instrumenter.MethodTransformer {

  // Added here instead of byte-buddy's ignores because it's relatively
  // expensive. https://github.com/DataDog/dd-trace-java/pull/1045
  private static final ElementMatcher.Junction<TypeDescription> NOT_DECORATOR_MATCHER =
      not(
          declaresAnnotation(
              namedOneOf("javax.decorator.Decorator", "jakarta.decorator.Decorator")));

  private static final KnownTypesIndex knownTypesIndex = KnownTypesIndex.readIndex();

  /** Associates context stores with the class-loader matchers to activate them. */
  private final Map<Map.Entry<String, String>, ElementMatcher<ClassLoader>> contextStoreInjection =
      new HashMap<>();

  private final AgentBuilder agentBuilder;

  private final List<MatchRecorder> matchers = new ArrayList<>();
  private final BitSet knownTypesMask;
  private AdviceStack[] transformers;
  private int nextSupplementaryId;

  // temporary buffer for collecting advice; reset for each instrumenter
  private final List<AgentBuilder.Transformer> advice = new ArrayList<>();
  private ElementMatcher<? super MethodDescription> ignoredMethods;

  /**
   * Post processor to be applied to instrumenter advices if they implement {@link
   * WithPostProcessor}
   */
  private Advice.PostProcessor.Factory postProcessor;

  public CombiningTransformerBuilder(AgentBuilder agentBuilder, int maxInstrumentationId) {
    this.agentBuilder = agentBuilder;
    int maxInstrumentationCount = maxInstrumentationId + 1;
    this.knownTypesMask = new BitSet(maxInstrumentationCount);
    this.transformers = new AdviceStack[maxInstrumentationCount];
    this.nextSupplementaryId = maxInstrumentationId + 1;
  }

  public void applyInstrumentation(InstrumenterModule module) {
    if (module.isEnabled()) {
      InstrumenterState.registerInstrumentation(module);
      for (Instrumenter member : module.typeInstrumentations()) {
        buildInstrumentation(module, member);
      }
    }
  }

  private void buildInstrumentation(InstrumenterModule module, Instrumenter member) {

    int id = module.instrumentationId();
    if (module != member) {
      // this is an additional "dd.trace.methods" instrumenter configured at runtime
      // (a separate instance is created for each class listed in "dd.trace.methods")
      // allocate a distinct id for matching purposes to avoid mixing trace methods
      id = nextSupplementaryId++;
      if (transformers.length <= id) {
        transformers = Arrays.copyOf(transformers, id + 1);
      }
    }

    buildInstrumentationMatcher(module, member, id);
    buildInstrumentationAdvice(module, member, id);
  }

  private void buildInstrumentationMatcher(InstrumenterModule module, Instrumenter member, int id) {

    if (member instanceof Instrumenter.ForSingleType) {
      String name = ((Instrumenter.ForSingleType) member).instrumentedType();
      if (knownTypesIndex.contains(name, id)) {
        knownTypesMask.set(id);
      } else {
        matchers.add(new MatchRecorder.ForType(id, named(name)));
      }
    } else if (member instanceof Instrumenter.ForKnownTypes) {
      String[] names = ((Instrumenter.ForKnownTypes) member).knownMatchingTypes();
      if (knownTypesIndex.contains(names[0], id)) {
        knownTypesMask.set(id);
      } else {
        matchers.add(new MatchRecorder.ForType(id, namedOneOf(names)));
      }
    } else if (member instanceof Instrumenter.ForTypeHierarchy) {
      matchers.add(new MatchRecorder.ForHierarchy(id, (Instrumenter.ForTypeHierarchy) member));
    } else if (member instanceof Instrumenter.ForCallSite) {
      matchers.add(new MatchRecorder.ForType(id, ((Instrumenter.ForCallSite) member).callerType()));
    }

    if (member instanceof Instrumenter.ForConfiguredTypes) {
      Collection<String> names =
          ((Instrumenter.ForConfiguredTypes) member).configuredMatchingTypes();
      if (null != names && !names.isEmpty()) {
        matchers.add(new MatchRecorder.ForType(id, namedOneOf(names)));
      }
    }

    if (member instanceof Instrumenter.CanShortcutTypeMatching
        && !((Instrumenter.CanShortcutTypeMatching) member).onlyMatchKnownTypes()) {
      matchers.add(new MatchRecorder.ForHierarchy(id, (Instrumenter.ForTypeHierarchy) member));
    }

    ElementMatcher<ClassLoader> classLoaderMatcher = module.classLoaderMatcher();
    if (classLoaderMatcher != ANY_CLASS_LOADER) {
      matchers.add(new MatchRecorder.NarrowLocation(id, classLoaderMatcher));
    }

    if (member instanceof Instrumenter.WithTypeStructure) {
      matchers.add(
          new MatchRecorder.NarrowType(
              id, ((Instrumenter.WithTypeStructure) member).structureMatcher()));
    }

    matchers.add(new MatchRecorder.NarrowLocation(id, new MuzzleCheck(module)));
  }

  private void buildInstrumentationAdvice(InstrumenterModule module, Instrumenter member, int id) {

    postProcessor =
        member instanceof WithPostProcessor ? ((WithPostProcessor) member).postProcessor() : null;

    String[] helperClassNames = module.helperClassNames();
    if (module.injectHelperDependencies()) {
      helperClassNames = HelperScanner.withClassDependencies(helperClassNames);
    }
    if (helperClassNames.length > 0) {
      advice.add(new HelperTransformer(module.getClass().getSimpleName(), helperClassNames));
    }

    Map<String, String> contextStore = module.contextStore();
    if (!contextStore.isEmpty()) {
      // rewrite context store access to call FieldBackedContextStores with assigned store-id
      advice.add(
          new VisitingTransformer(
              new FieldBackedContextRequestRewriter(contextStore, module.name())));

      registerContextStoreInjection(module, member, contextStore);
    }

    ignoredMethods = module.methodIgnoreMatcher();
    if (member instanceof Instrumenter.HasTypeAdvice) {
      ((Instrumenter.HasTypeAdvice) member).typeAdvice(this);
    }
    if (member instanceof Instrumenter.HasMethodAdvice) {
      ((Instrumenter.HasMethodAdvice) member).methodAdvice(this);
    }
    transformers[id] = new AdviceStack(advice);

    advice.clear();
  }

  @Override
  public void applyAdvice(Instrumenter.TransformingAdvice typeAdvice) {
    advice.add(typeAdvice::transform);
  }

  @Override
  public void applyAdvice(ElementMatcher<? super MethodDescription> matcher, String adviceClass) {
    Advice.WithCustomMapping customMapping = Advice.withCustomMapping();
    if (postProcessor != null) {
      customMapping = customMapping.with(postProcessor);
    }
    advice.add(
        new AgentBuilder.Transformer.ForAdvice(customMapping)
            .include(Utils.getBootstrapProxy(), Utils.getAgentClassLoader())
            .withExceptionHandler(ExceptionHandlers.defaultExceptionHandler())
            .advice(not(ignoredMethods).and(matcher), adviceClass));
  }

  /** Counts the number of distinct context store injections registered with this builder. */
  private int contextStoreCount() {
    return contextStoreInjection.size();
  }

  /** Applies each context store injection, guarded by the associated class-loader matcher. */
  private void applyContextStoreInjection() {
    contextStoreInjection.forEach(this::applyContextStoreInjection);
  }

  /** Tracks which class-loader matchers are associated with each store request. */
  private void registerContextStoreInjection(
      InstrumenterModule module, Instrumenter member, Map<String, String> contextStore) {
    ElementMatcher<ClassLoader> activation;

    if (member instanceof Instrumenter.ForBootstrap) {
      activation = ANY_CLASS_LOADER;
    } else if (member instanceof Instrumenter.ForTypeHierarchy) {
      String hierarchyHint = ((Instrumenter.ForTypeHierarchy) member).hierarchyMarkerType();
      activation = null != hierarchyHint ? hasClassNamed(hierarchyHint) : ANY_CLASS_LOADER;
    } else if (member instanceof Instrumenter.ForSingleType) {
      activation = hasClassNamed(((Instrumenter.ForSingleType) member).instrumentedType());
    } else if (member instanceof Instrumenter.ForKnownTypes) {
      activation = hasClassNamedOneOf(((Instrumenter.ForKnownTypes) member).knownMatchingTypes());
    } else {
      activation = ANY_CLASS_LOADER;
    }

    activation = requireBoth(activation, module.classLoaderMatcher());

    for (Map.Entry<String, String> storeEntry : contextStore.entrySet()) {
      ElementMatcher<ClassLoader> oldActivation = contextStoreInjection.get(storeEntry);
      // optimization: treat 'any' as if there wasn't an old matcher
      if (null == oldActivation || ANY_CLASS_LOADER == activation) {
        contextStoreInjection.put(storeEntry, activation);
      } else if (ANY_CLASS_LOADER != oldActivation) {
        // store can be activated by either the old OR new matcher
        contextStoreInjection.put(
            storeEntry, new ElementMatcher.Junction.Disjunction<>(oldActivation, activation));
      }
    }
  }

  /** Arranges for a context value field to be injected into types extending the context key. */
  private void applyContextStoreInjection(
      Map.Entry<String, String> contextStore, ElementMatcher<ClassLoader> activation) {
    String keyClassName = contextStore.getKey();
    String contextClassName = contextStore.getValue();

    FieldBackedContextMatcher contextMatcher =
        new FieldBackedContextMatcher(keyClassName, contextClassName);
    FieldBackedContextInjector contextAdvice =
        new FieldBackedContextInjector(keyClassName, contextClassName);

    int id = nextSupplementaryId++;

    matchers.add(new MatchRecorder.ForContextStore(id, activation, contextMatcher));
    transformers[id] = new AdviceStack(new VisitingTransformer(contextAdvice));
  }

  public ClassFileTransformer installOn(Instrumentation instrumentation) {
    if (InstrumenterConfig.get().isRuntimeContextFieldInjection()) {
      // expand so we have enough space for a context injecting transformer for each store
      transformers = Arrays.copyOf(transformers, transformers.length + contextStoreCount());
      applyContextStoreInjection();
    }

    return agentBuilder
        .type(new CombiningMatcher(knownTypesIndex, knownTypesMask, matchers))
        .and(NOT_DECORATOR_MATCHER)
        .transform(defaultTransformers())
        .transform(new SplittingTransformer(transformers))
        .installOn(instrumentation);
  }

  static final class VisitingTransformer implements AgentBuilder.Transformer {
    private final AsmVisitorWrapper visitor;

    VisitingTransformer(AsmVisitorWrapper visitor) {
      this.visitor = visitor;
    }

    @Override
    public DynamicType.Builder<?> transform(
        DynamicType.Builder<?> builder,
        TypeDescription typeDescription,
        ClassLoader classLoader,
        JavaModule module,
        ProtectionDomain pd) {
      return builder.visit(visitor);
    }
  }

  static final class HelperTransformer extends HelperInjector implements AgentBuilder.Transformer {
    HelperTransformer(String requestingName, String... helperClassNames) {
      super(requestingName, helperClassNames);
    }
  }

  static ElementMatcher<ClassLoader> requireBoth(
      ElementMatcher<ClassLoader> lhs, ElementMatcher<ClassLoader> rhs) {
    if (ANY_CLASS_LOADER == lhs) {
      return rhs;
    } else if (ANY_CLASS_LOADER == rhs) {
      return lhs;
    } else {
      return new ElementMatcher.Junction.Conjunction<>(lhs, rhs);
    }
  }
}
