package datadog.trace.agent.tooling;

import static datadog.trace.agent.tooling.bytebuddy.DDTransformers.defaultTransformers;
import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.ANY_CLASS_LOADER;
import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;
import static net.bytebuddy.matcher.ElementMatchers.isSynthetic;
import static net.bytebuddy.matcher.ElementMatchers.none;
import static net.bytebuddy.matcher.ElementMatchers.not;

import datadog.trace.agent.tooling.bytebuddy.ExceptionHandlers;
import datadog.trace.agent.tooling.bytebuddy.matcher.FailSafeRawMatcher;
import datadog.trace.agent.tooling.bytebuddy.matcher.InjectContextFieldMatcher;
import datadog.trace.agent.tooling.bytebuddy.matcher.KnownTypesMatcher;
import datadog.trace.agent.tooling.bytebuddy.matcher.MuzzleMatcher;
import datadog.trace.agent.tooling.bytebuddy.matcher.SingleTypeMatcher;
import datadog.trace.agent.tooling.context.FieldBackedContextInjector;
import datadog.trace.agent.tooling.context.FieldBackedContextRequestRewriter;
import datadog.trace.api.InstrumenterConfig;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.util.Collection;
import java.util.Map;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

public final class LegacyTransformerBuilder extends AbstractTransformerBuilder {

  private AgentBuilder agentBuilder;
  private ElementMatcher<? super MethodDescription> ignoreMatcher;
  private AgentBuilder.Identified.Extendable adviceBuilder;

  LegacyTransformerBuilder(AgentBuilder agentBuilder) {
    this.agentBuilder = agentBuilder;
  }

  @Override
  public ClassFileTransformer installOn(Instrumentation instrumentation) {
    if (InstrumenterConfig.get().isRuntimeContextFieldInjection()) {
      applyContextStoreInjection();
    }

    return agentBuilder.installOn(instrumentation);
  }

  @Override
  protected void buildInstrumentation(InstrumenterModule module, Instrumenter member) {

    ignoreMatcher = module.methodIgnoreMatcher();
    adviceBuilder =
        agentBuilder
            .type(typeMatcher(module, member))
            .and(NOT_DECORATOR_MATCHER)
            .and(new MuzzleMatcher(module))
            .transform(defaultTransformers());

    String[] helperClassNames = module.helperClassNames();
    if (module.injectHelperDependencies()) {
      helperClassNames = HelperScanner.withClassDependencies(helperClassNames);
    }
    if (helperClassNames.length > 0) {
      adviceBuilder =
          adviceBuilder.transform(
              new HelperTransformer(module.getClass().getSimpleName(), helperClassNames));
    }

    Map<String, String> contextStore = module.contextStore();
    if (!contextStore.isEmpty()) {
      // rewrite context store access to call FieldBackedContextStores with assigned store-id
      adviceBuilder =
          adviceBuilder.transform(
              new VisitingTransformer(
                  new FieldBackedContextRequestRewriter(contextStore, module.name())));

      registerContextStoreInjection(module, member, contextStore);
    }

    agentBuilder = registerAdvice(member);
  }

  private AgentBuilder registerAdvice(Instrumenter instrumenter) {
    if (instrumenter instanceof Instrumenter.HasTypeAdvice) {
      ((Instrumenter.HasTypeAdvice) instrumenter).typeAdvice(this);
    }
    if (instrumenter instanceof Instrumenter.HasMethodAdvice) {
      ((Instrumenter.HasMethodAdvice) instrumenter).methodAdvice(this);
    }
    return adviceBuilder;
  }

  private AgentBuilder.RawMatcher typeMatcher(InstrumenterModule module, Instrumenter member) {
    ElementMatcher<? super TypeDescription> typeMatcher;
    String hierarchyHint = null;

    if (member instanceof Instrumenter.ForSingleType) {
      String name = ((Instrumenter.ForSingleType) member).instrumentedType();
      typeMatcher = new SingleTypeMatcher(name);
    } else if (member instanceof Instrumenter.ForKnownTypes) {
      String[] names = ((Instrumenter.ForKnownTypes) member).knownMatchingTypes();
      typeMatcher = new KnownTypesMatcher(names);
    } else if (member instanceof Instrumenter.ForTypeHierarchy) {
      typeMatcher = ((Instrumenter.ForTypeHierarchy) member).hierarchyMatcher();
      hierarchyHint = ((Instrumenter.ForTypeHierarchy) member).hierarchyMarkerType();
    } else if (member instanceof Instrumenter.ForConfiguredTypes) {
      typeMatcher = none(); // handle below, just like when it's combined with other matchers
    } else if (member instanceof Instrumenter.ForCallSite) {
      typeMatcher = ((Instrumenter.ForCallSite) member).callerType();
    } else {
      return AgentBuilder.RawMatcher.Trivial.NON_MATCHING;
    }

    if (member instanceof Instrumenter.CanShortcutTypeMatching
        && !((Instrumenter.CanShortcutTypeMatching) member).onlyMatchKnownTypes()) {
      // not taking shortcuts, so include wider hierarchical matching
      typeMatcher =
          new ElementMatcher.Junction.Disjunction(
              typeMatcher, ((Instrumenter.ForTypeHierarchy) member).hierarchyMatcher());
      hierarchyHint = ((Instrumenter.ForTypeHierarchy) member).hierarchyMarkerType();
    }

    if (member instanceof Instrumenter.ForConfiguredTypes) {
      Collection<String> names =
          ((Instrumenter.ForConfiguredTypes) member).configuredMatchingTypes();
      // only add this optional matcher when it's been configured
      if (null != names && !names.isEmpty()) {
        typeMatcher =
            new ElementMatcher.Junction.Disjunction(typeMatcher, new KnownTypesMatcher(names));
      }
    }

    if (member instanceof Instrumenter.WithTypeStructure) {
      // only perform structure matching after we've matched the type
      typeMatcher =
          new ElementMatcher.Junction.Conjunction(
              typeMatcher, ((Instrumenter.WithTypeStructure) member).structureMatcher());
    }

    ElementMatcher<ClassLoader> classLoaderMatcher = module.classLoaderMatcher();

    if (null != hierarchyHint) {
      // use hint to limit expensive type matching to class-loaders with marker type
      classLoaderMatcher = requireBoth(hasClassNamed(hierarchyHint), classLoaderMatcher);
    }

    if (ANY_CLASS_LOADER == classLoaderMatcher && typeMatcher instanceof AgentBuilder.RawMatcher) {
      // optimization when using raw (named) type matcher with no classloader filtering
      return (AgentBuilder.RawMatcher) typeMatcher;
    }

    return new FailSafeRawMatcher(
        typeMatcher,
        classLoaderMatcher,
        "Instrumentation matcher unexpected exception - instrumentation.names="
            + module.names()
            + " instrumentation.class="
            + module.getClass().getName());
  }

  @Override
  protected void buildSingleAdvice(Instrumenter.ForSingleType instrumenter) {
    AgentBuilder.RawMatcher matcher = new SingleTypeMatcher(instrumenter.instrumentedType());

    ignoreMatcher = isSynthetic();
    adviceBuilder =
        agentBuilder.type(matcher).and(NOT_DECORATOR_MATCHER).transform(defaultTransformers());

    agentBuilder = registerAdvice((Instrumenter) instrumenter);
  }

  @Override
  public void applyAdvice(Instrumenter.TransformingAdvice typeAdvice) {
    adviceBuilder = adviceBuilder.transform(typeAdvice::transform);
  }

  @Override
  public void applyAdvice(ElementMatcher<? super MethodDescription> matcher, String className) {
    adviceBuilder =
        adviceBuilder.transform(
            new AgentBuilder.Transformer.ForAdvice()
                .include(Utils.getBootstrapProxy(), Utils.getAgentClassLoader())
                .withExceptionHandler(ExceptionHandlers.defaultExceptionHandler())
                .advice(not(ignoreMatcher).and(matcher), className));
  }

  @Override
  protected void applyContextStoreInjection(
      Map.Entry<String, String> contextStore, ElementMatcher<ClassLoader> activation) {
    String keyClassName = contextStore.getKey();
    String contextClassName = contextStore.getValue();
    agentBuilder =
        agentBuilder
            .type(new InjectContextFieldMatcher(keyClassName, contextClassName, activation))
            .and(NOT_DECORATOR_MATCHER)
            .transform(
                new VisitingTransformer(
                    new FieldBackedContextInjector(keyClassName, contextClassName)));
  }
}
