package datadog.trace.agent.tooling;

import static datadog.trace.agent.tooling.bytebuddy.DDTransformers.defaultTransformers;
import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.ANY_CLASS_LOADER;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.isSynthetic;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.matcher.ElementMatcher;

/** Builds multiple instrumentations into a single combining-matcher and splitting-transformer. */
public final class CombiningTransformerBuilder extends AbstractTransformerBuilder {
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

  @Override
  protected void buildInstrumentation(Instrumenter.Default instrumenter) {
    InstrumenterState.registerInstrumentation(instrumenter);

    int id = instrumenter.instrumentationId();
    if (transformers[id] != null) {
      // this is an additional "dd.trace.methods" instrumenter configured at runtime
      // (a separate instance is created for each class listed in "dd.trace.methods")
      // allocate a distinct id for matching purposes to avoid mixing trace methods
      id = nextSupplementaryId++;
      if (transformers.length <= id) {
        transformers = Arrays.copyOf(transformers, id + 1);
      }
    }

    buildInstrumentationMatcher(instrumenter, id);
    buildInstrumentationAdvice(instrumenter, id);
  }

  private void buildInstrumentationMatcher(Instrumenter.Default instrumenter, int id) {

    if (instrumenter instanceof Instrumenter.ForSingleType
        || instrumenter instanceof Instrumenter.ForKnownTypes) {
      knownTypesMask.set(id);
    } else if (instrumenter instanceof Instrumenter.ForTypeHierarchy) {
      matchers.add(
          new MatchRecorder.ForHierarchy(id, (Instrumenter.ForTypeHierarchy) instrumenter));
    } else if (instrumenter instanceof Instrumenter.ForCallSite) {
      matchers.add(
          new MatchRecorder.ForType(id, ((Instrumenter.ForCallSite) instrumenter).callerType()));
    }

    if (instrumenter instanceof Instrumenter.ForConfiguredTypes) {
      Collection<String> names =
          ((Instrumenter.ForConfiguredTypes) instrumenter).configuredMatchingTypes();
      if (null != names && !names.isEmpty()) {
        matchers.add(new MatchRecorder.ForType(id, namedOneOf(names)));
      }
    }

    if (instrumenter instanceof Instrumenter.CanShortcutTypeMatching
        && !((Instrumenter.CanShortcutTypeMatching) instrumenter).onlyMatchKnownTypes()) {
      matchers.add(
          new MatchRecorder.ForHierarchy(id, (Instrumenter.ForTypeHierarchy) instrumenter));
    }

    ElementMatcher<ClassLoader> classLoaderMatcher = instrumenter.classLoaderMatcher();
    if (classLoaderMatcher != ANY_CLASS_LOADER) {
      matchers.add(new MatchRecorder.NarrowLocation(id, classLoaderMatcher));
    }

    if (instrumenter instanceof Instrumenter.WithTypeStructure) {
      matchers.add(
          new MatchRecorder.NarrowType(
              id, ((Instrumenter.WithTypeStructure) instrumenter).structureMatcher()));
    }

    matchers.add(new MatchRecorder.NarrowLocation(id, new MuzzleCheck(instrumenter)));
  }

  private void buildInstrumentationAdvice(Instrumenter.Default instrumenter, int id) {

    postProcessor =
        instrumenter instanceof WithPostProcessor
            ? ((WithPostProcessor) instrumenter).postProcessor()
            : null;

    String[] helperClassNames = instrumenter.helperClassNames();
    if (instrumenter.injectHelperDependencies()) {
      helperClassNames = HelperScanner.withClassDependencies(helperClassNames);
    }
    if (helperClassNames.length > 0) {
      advice.add(new HelperTransformer(instrumenter.getClass().getSimpleName(), helperClassNames));
    }

    Map<String, String> contextStore = instrumenter.contextStore();
    if (!contextStore.isEmpty()) {
      // rewrite context store access to call FieldBackedContextStores with assigned store-id
      advice.add(
          new VisitingTransformer(
              new FieldBackedContextRequestRewriter(contextStore, instrumenter.name())));

      registerContextStoreInjection(instrumenter, contextStore);
    }

    ignoredMethods = instrumenter.methodIgnoreMatcher();
    instrumenter.typeAdvice(this);
    instrumenter.methodAdvice(this);
    transformers[id] = new AdviceStack(advice);

    advice.clear();
  }

  @Override
  protected void buildSingleAdvice(Instrumenter.ForSingleType instrumenter) {

    // this is a test instrumenter which needs a dynamic id
    int id = nextSupplementaryId++;
    if (transformers.length <= id) {
      transformers = Arrays.copyOf(transformers, id + 1);
    }

    // can't use known-types index because it doesn't include test instrumenters
    matchers.add(new MatchRecorder.ForType(id, named(instrumenter.instrumentedType())));

    ignoredMethods = isSynthetic();
    ((Instrumenter.HasAdvice) instrumenter).typeAdvice(this);
    ((Instrumenter.HasAdvice) instrumenter).methodAdvice(this);
    transformers[id] = new AdviceStack(advice);

    advice.clear();
  }

  @Override
  public void applyAdvice(Instrumenter.TransformingAdvice typeAdvice) {
    advice.add(typeAdvice::transform);
  }

  @Override
  public void applyAdvice(ElementMatcher<? super MethodDescription> matcher, String className) {
    Advice.WithCustomMapping customMapping = Advice.withCustomMapping();
    if (postProcessor != null) {
      customMapping = customMapping.with(postProcessor);
    }
    advice.add(
        new AgentBuilder.Transformer.ForAdvice(customMapping)
            .include(Utils.getBootstrapProxy(), Utils.getAgentClassLoader())
            .withExceptionHandler(ExceptionHandlers.defaultExceptionHandler())
            .advice(not(ignoredMethods).and(matcher), className));
  }

  @Override
  protected void applyContextStoreInjection(
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

  @Override
  public ClassFileTransformer installOn(Instrumentation instrumentation) {
    if (InstrumenterConfig.get().isRuntimeContextFieldInjection()) {
      // expand so we have enough space for a context injecting transformer for each store
      transformers = Arrays.copyOf(transformers, transformers.length + contextStoreCount());
      applyContextStoreInjection();
    }

    return agentBuilder
        .type(new CombiningMatcher(knownTypesMask, matchers))
        .and(NOT_DECORATOR_MATCHER)
        .transform(defaultTransformers())
        .transform(new SplittingTransformer(transformers))
        .installOn(instrumentation);
  }
}
