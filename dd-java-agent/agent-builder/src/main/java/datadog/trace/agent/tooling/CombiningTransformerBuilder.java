package datadog.trace.agent.tooling;

import static datadog.trace.agent.tooling.bytebuddy.DDTransformers.defaultTransformers;
import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.ANY_CLASS_LOADER;
import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamedOneOf;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.declaresAnnotation;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static java.util.Collections.emptySet;
import static net.bytebuddy.matcher.ElementMatchers.not;

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
import java.util.Set;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.utility.JavaModule;

/**
 * Builds {@link InstrumenterModule}s into a single combining-matcher and splitting-transformer.
 *
 * <p>Each transformation defined by a module is allocated a unique {@code transformationId} used to
 * combine match results in a bitset. This bitset determines the transformations to apply to a type.
 */
public final class CombiningTransformerBuilder
    implements Instrumenter.TypeTransformer, Instrumenter.MethodTransformer {

  // Added here instead of byte-buddy's ignores because it's relatively
  // expensive. https://github.com/DataDog/dd-trace-java/pull/1045
  private static final ElementMatcher.Junction<TypeDescription> NOT_DECORATOR_MATCHER =
      not(
          declaresAnnotation(
              namedOneOf("javax.decorator.Decorator", "jakarta.decorator.Decorator")));

  /** Associates context stores with the class-loader matchers to activate them. */
  private final Map<Map.Entry<String, String>, ElementMatcher<ClassLoader>> contextStoreInjection =
      new HashMap<>();

  private final AgentBuilder agentBuilder;
  private final InstrumenterIndex instrumenterIndex;
  private final int knownTransformationCount;

  private final List<MatchRecorder> matchers = new ArrayList<>();
  private final BitSet knownTypesMask;
  private AdviceStack[] transformers;

  // used to allocate ids to instrumentations not known at build-time
  private int nextRuntimeInstrumentationId;
  private int nextRuntimeTransformationId;

  // module defined matchers and transformers, shared across members
  private ElementMatcher<? super MethodDescription> ignoredMethods;
  private ElementMatcher<ClassLoader> classLoaderMatcher;
  private Map<String, String> contextStore;
  private AgentBuilder.Transformer contextRequestRewriter;
  private AdviceShader adviceShader;
  private HelperTransformer helperTransformer;
  private Advice.PostProcessor.Factory postProcessor;
  private MuzzleCheck muzzle;
  private Set<String> generalPurposeAdviceClasses = null;

  // temporary buffer for collecting advice; reset for each instrumenter
  private final List<AgentBuilder.Transformer> advice = new ArrayList<>();

  public CombiningTransformerBuilder(
      AgentBuilder agentBuilder, InstrumenterIndex instrumenterIndex) {
    this.agentBuilder = agentBuilder;
    this.instrumenterIndex = instrumenterIndex;
    int knownInstrumentationCount = instrumenterIndex.instrumentationCount();
    this.knownTransformationCount = instrumenterIndex.transformationCount();
    this.knownTypesMask = new BitSet(knownTransformationCount);
    this.transformers = new AdviceStack[knownTransformationCount];
    this.nextRuntimeInstrumentationId = knownInstrumentationCount;
    this.nextRuntimeTransformationId = knownTransformationCount;
  }

  /** Builds matchers and transformers for an instrumentation module and its members. */
  public void applyInstrumentation(
      InstrumenterModule module, boolean isModuleApplicableOnTargetSystems) {
    if (module.isEnabled()) {
      int instrumentationId = instrumenterIndex.instrumentationId(module);
      if (instrumentationId < 0) {
        // this is a non-indexed instrumentation configured at runtime
        instrumentationId = nextRuntimeInstrumentationId++;
      }
      InstrumenterState.registerInstrumentation(module, instrumentationId);
      prepareInstrumentation(module, instrumentationId, isModuleApplicableOnTargetSystems);
      for (Instrumenter member : module.typeInstrumentations()) {
        buildTypeInstrumentation(member);
      }
    }
  }

  /** Prepares shared matchers and transformers defined by an instrumentation module. */
  private void prepareInstrumentation(
      InstrumenterModule module, int instrumentationId, boolean isModuleApplicableOnTargetSystems) {
    ignoredMethods = module.methodIgnoreMatcher();
    classLoaderMatcher = module.classLoaderMatcher();
    contextStore = module.contextStore();

    contextRequestRewriter =
        !contextStore.isEmpty()
            ? new VisitingTransformer(
                new FieldBackedContextRequestRewriter(contextStore, module.name()))
            : null;

    adviceShader = AdviceShader.with(module);

    String[] helperClassNames = module.helperClassNames();
    if (module.injectHelperDependencies()) {
      helperClassNames = HelperScanner.withClassDependencies(helperClassNames);
    }
    helperTransformer =
        helperClassNames.length > 0
            ? new HelperTransformer(
                module.useAgentCodeSource(),
                adviceShader,
                module.getClass().getSimpleName(),
                helperClassNames)
            : null;

    postProcessor = module.postProcessor();

    muzzle = new MuzzleCheck(module, instrumentationId);

    if (!isModuleApplicableOnTargetSystems) {
      if (module instanceof Instrumenter.HasGeneralPurposeAdvices) {
        generalPurposeAdviceClasses =
            ((Instrumenter.HasGeneralPurposeAdvices) module).generalPurposeAdviceClasses();
        if (generalPurposeAdviceClasses == null) {
          this.generalPurposeAdviceClasses = emptySet();
        }
      }
    }
  }

  /** Builds a type-specific transformer, controlled by one or more matchers. */
  private void buildTypeInstrumentation(Instrumenter member) {

    int transformationId = instrumenterIndex.transformationId(member);
    if (transformationId < 0) {
      // this is a non-indexed transformation configured at runtime, e.g. "dd.trace.methods"
      // allocate a distinct runtime id to each extra transformation for matching purposes
      transformationId = nextRuntimeTransformationId++;
      if (transformers.length <= transformationId) {
        transformers = Arrays.copyOf(transformers, transformationId + 1);
      }
    }

    buildTypeMatcher(member, transformationId);
    buildTypeAdvice(member, transformationId);
  }

  private void buildTypeMatcher(Instrumenter member, int transformationId) {

    if (member instanceof Instrumenter.ForSingleType) {
      if (transformationId < knownTransformationCount) {
        knownTypesMask.set(transformationId); // can use known-types index
      } else {
        String name = ((Instrumenter.ForSingleType) member).instrumentedType();
        matchers.add(new MatchRecorder.ForType(transformationId, named(name)));
      }
    } else if (member instanceof Instrumenter.ForKnownTypes) {
      if (transformationId < knownTransformationCount) {
        knownTypesMask.set(transformationId); // can use known-types index
      } else {
        String[] names = ((Instrumenter.ForKnownTypes) member).knownMatchingTypes();
        matchers.add(new MatchRecorder.ForType(transformationId, namedOneOf(names)));
      }
    } else if (member instanceof Instrumenter.ForTypeHierarchy) {
      matchers.add(
          new MatchRecorder.ForHierarchy(transformationId, (Instrumenter.ForTypeHierarchy) member));
    } else if (member instanceof Instrumenter.ForCallSite) {
      matchers.add(
          new MatchRecorder.ForType(
              transformationId, ((Instrumenter.ForCallSite) member).callerType()));
    }

    if (member instanceof Instrumenter.ForConfiguredTypes) {
      Collection<String> names =
          ((Instrumenter.ForConfiguredTypes) member).configuredMatchingTypes();
      if (null != names && !names.isEmpty()) {
        matchers.add(new MatchRecorder.ForType(transformationId, namedOneOf(names)));
      }
    }

    if (member instanceof Instrumenter.CanShortcutTypeMatching
        && !((Instrumenter.CanShortcutTypeMatching) member).onlyMatchKnownTypes()) {
      matchers.add(
          new MatchRecorder.ForHierarchy(transformationId, (Instrumenter.ForTypeHierarchy) member));
    }

    if (classLoaderMatcher != ANY_CLASS_LOADER) {
      matchers.add(new MatchRecorder.NarrowLocation(transformationId, classLoaderMatcher));
    }

    if (member instanceof Instrumenter.WithTypeStructure) {
      matchers.add(
          new MatchRecorder.NarrowType(
              transformationId, ((Instrumenter.WithTypeStructure) member).structureMatcher()));
    }

    matchers.add(new MatchRecorder.NarrowLocation(transformationId, muzzle));
  }

  private void buildTypeAdvice(Instrumenter member, int transformationId) {

    if (null != helperTransformer) {
      advice.add(helperTransformer);
    }

    if (null != contextRequestRewriter) {
      registerContextStoreInjection(member, contextStore);
      // rewrite context store access to call FieldBackedContextStores with assigned store-id
      advice.add(contextRequestRewriter);
    }

    if (member instanceof Instrumenter.HasTypeAdvice) {
      ((Instrumenter.HasTypeAdvice) member).typeAdvice(this);
    }
    if (member instanceof Instrumenter.HasMethodAdvice) {
      ((Instrumenter.HasMethodAdvice) member).methodAdvice(this);
    }

    // record the advice collected for this transformationId
    transformers[transformationId] = new AdviceStack(advice);

    advice.clear(); // reset for next transformationId
  }

  @Override
  public void applyAdvice(Instrumenter.TransformingAdvice typeAdvice) {
    advice.add(typeAdvice::transform);
  }

  @Override
  public void applyAdvice(
      ElementMatcher<? super MethodDescription> matcher,
      String adviceClass,
      String... additionalAdviceClasses) {
    Advice.WithCustomMapping customMapping = Advice.withCustomMapping();
    if (postProcessor != null) {
      customMapping = customMapping.with(postProcessor);
    }
    AgentBuilder.Transformer.ForAdvice forAdvice =
        new AgentBuilder.Transformer.ForAdvice(customMapping)
            .withExceptionHandler(ExceptionHandlers.defaultExceptionHandler())
            .include(Utils.getBootstrapProxy());
    ClassLoader adviceLoader = Utils.getExtendedClassLoader();
    if (adviceShader != null) {
      forAdvice = forAdvice.include(new ShadedAdviceLocator(adviceLoader, adviceShader));
    } else {
      forAdvice = forAdvice.include(adviceLoader);
    }
    if (generalPurposeAdviceClasses == null || generalPurposeAdviceClasses.contains(adviceClass)) {
      forAdvice = forAdvice.advice(not(ignoredMethods).and(matcher), adviceClass);
    }
    if (additionalAdviceClasses != null) {
      for (String adviceClassName : additionalAdviceClasses) {
        if (generalPurposeAdviceClasses == null
            || generalPurposeAdviceClasses.contains(adviceClassName)) {
          forAdvice = forAdvice.advice(not(ignoredMethods).and(matcher), adviceClassName);
        }
      }
    }
    advice.add(forAdvice);
  }

  public ClassFileTransformer installOn(Instrumentation instrumentation) {
    if (InstrumenterConfig.get().isRuntimeContextFieldInjection()) {
      applyContextStoreInjection();
    }

    return agentBuilder
        .type(new CombiningMatcher(instrumentation, knownTypesMask, matchers))
        .and(NOT_DECORATOR_MATCHER)
        .transform(defaultTransformers())
        .transform(new SplittingTransformer(transformers))
        .installOn(instrumentation);
  }

  /** Counts the number of distinct context store injections registered with this builder. */
  private int contextStoreCount() {
    return contextStoreInjection.size();
  }

  /** Tracks which class-loader matchers are associated with each store request. */
  private void registerContextStoreInjection(
      Instrumenter member, Map<String, String> contextStore) {
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

    activation = requireBoth(activation, classLoaderMatcher);

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

  /** Applies each context store injection, guarded by the associated class-loader matcher. */
  private void applyContextStoreInjection() {
    // expand array so we have enough space for a context injecting transformer for each store
    transformers = Arrays.copyOf(transformers, transformers.length + contextStoreCount());

    contextStoreInjection.forEach(this::applyContextStoreInjection);
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

    // transformers array has already been expanded to fit in 'applyContextStoreInjection()'
    int transformationId = nextRuntimeTransformationId++;

    matchers.add(new MatchRecorder.ForContextStore(transformationId, activation, contextMatcher));
    transformers[transformationId] = new AdviceStack(new VisitingTransformer(contextAdvice));
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
    HelperTransformer(
        boolean useAgentCodeSource,
        AdviceShader adviceShader,
        String requestingName,
        String... helperClassNames) {
      super(useAgentCodeSource, adviceShader, requestingName, helperClassNames);
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
