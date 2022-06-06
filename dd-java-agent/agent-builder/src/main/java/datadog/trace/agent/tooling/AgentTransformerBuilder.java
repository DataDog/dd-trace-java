package datadog.trace.agent.tooling;

import static datadog.trace.agent.tooling.bytebuddy.DDTransformers.defaultTransformers;
import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.ANY_CLASS_LOADER;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.declaresAnnotation;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isSynthetic;
import static net.bytebuddy.matcher.ElementMatchers.none;
import static net.bytebuddy.matcher.ElementMatchers.not;

import datadog.trace.agent.tooling.bytebuddy.ExceptionHandlers;
import datadog.trace.agent.tooling.bytebuddy.matcher.FailSafeRawMatcher;
import datadog.trace.agent.tooling.bytebuddy.matcher.KnownTypesMatcher;
import datadog.trace.agent.tooling.bytebuddy.matcher.SingleTypeMatcher;
import datadog.trace.agent.tooling.context.FieldBackedContextProvider;
import datadog.trace.agent.tooling.context.InstrumentationContextProvider;
import datadog.trace.agent.tooling.context.NoopContextProvider;
import datadog.trace.api.Config;
import datadog.trace.api.IntegrationsCollector;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.Map;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.utility.JavaModule;

public class AgentTransformerBuilder
    implements Instrumenter.TransformerBuilder, Instrumenter.AdviceTransformation {

  // Added here instead of byte-buddy's ignores because it's relatively
  // expensive. https://github.com/DataDog/dd-trace-java/pull/1045
  public static final ElementMatcher.Junction<TypeDescription> NOT_DECORATOR_MATCHER =
      not(declaresAnnotation(named("javax.decorator.Decorator")));

  private AgentBuilder agentBuilder;
  private ElementMatcher<? super MethodDescription> ignoreMatcher;
  private AgentBuilder.Identified.Extendable adviceBuilder;

  AgentTransformerBuilder(AgentBuilder agentBuilder) {
    this.agentBuilder = agentBuilder;
  }

  @Override
  public void applyInstrumentation(Instrumenter.HasAdvice instrumenter) {
    if (instrumenter instanceof Instrumenter.Default) {
      agentBuilder = buildInstrumentation((Instrumenter.Default) instrumenter);
    } else if (instrumenter instanceof Instrumenter.ForSingleType) {
      agentBuilder = buildSingleAdvice(instrumenter); // for testing purposes
    } else {
      throw new IllegalArgumentException("Unexpected Instrumenter type");
    }
  }

  public ResettableClassFileTransformer installOn(Instrumentation instrumentation) {
    return agentBuilder.installOn(instrumentation);
  }

  private AgentBuilder buildInstrumentation(final Instrumenter.Default instrumenter) {
    AgentBuilder.RawMatcher matcher = matcher(instrumenter);

    ignoreMatcher = instrumenter.methodIgnoreMatcher();
    adviceBuilder =
        agentBuilder
            .type(matcher)
            .and(NOT_DECORATOR_MATCHER)
            .and(
                new AgentBuilder.RawMatcher() {
                  @Override
                  public boolean matches(
                      TypeDescription typeDescription,
                      ClassLoader classLoader,
                      JavaModule module,
                      Class<?> classBeingRedefined,
                      ProtectionDomain protectionDomain) {
                    boolean isMatch = instrumenter.muzzleMatches(classLoader, classBeingRedefined);
                    if (isMatch && Config.get().isTelemetryEnabled()) {
                      IntegrationsCollector.get().update(instrumenter.names(), true);
                    }
                    return isMatch;
                  }
                })
            .transform(defaultTransformers());

    String[] helperClassNames = instrumenter.helperClassNames();
    if (helperClassNames.length > 0) {
      adviceBuilder =
          adviceBuilder.transform(
              new HelperTransformer(instrumenter.getClass().getSimpleName(), helperClassNames));
    }

    InstrumentationContextProvider contextProvider;
    Map<String, String> matchedContextStores = instrumenter.contextStore();
    if (matchedContextStores.isEmpty()) {
      contextProvider = NoopContextProvider.INSTANCE;
    } else {
      contextProvider =
          new FieldBackedContextProvider(
              instrumenter, singletonMap(instrumenter.classLoaderMatcher(), matchedContextStores));
    }

    adviceBuilder = contextProvider.instrumentationTransformer(adviceBuilder);

    final Instrumenter.AdviceTransformer customTransformer = instrumenter.transformer();
    if (customTransformer != null) {
      adviceBuilder =
          adviceBuilder.transform(
              new AgentBuilder.Transformer() {
                @Override
                public DynamicType.Builder<?> transform(
                    DynamicType.Builder<?> builder,
                    TypeDescription typeDescription,
                    ClassLoader classLoader,
                    JavaModule module) {
                  return customTransformer.transform(builder, typeDescription, classLoader, module);
                }
              });
    }

    instrumenter.adviceTransformations(this);

    adviceBuilder = contextProvider.additionalInstrumentation(adviceBuilder);

    return adviceBuilder;
  }

  private AgentBuilder.RawMatcher matcher(Instrumenter.Default instrumenter) {
    ElementMatcher<? super TypeDescription> typeMatcher;
    if (instrumenter instanceof Instrumenter.ForSingleType) {
      String name = ((Instrumenter.ForSingleType) instrumenter).instrumentedType();
      typeMatcher = new SingleTypeMatcher(name);
    } else if (instrumenter instanceof Instrumenter.ForKnownTypes) {
      String[] names = ((Instrumenter.ForKnownTypes) instrumenter).knownMatchingTypes();
      typeMatcher = new KnownTypesMatcher(names);
    } else if (instrumenter instanceof Instrumenter.ForTypeHierarchy) {
      typeMatcher = ((Instrumenter.ForTypeHierarchy) instrumenter).hierarchyMatcher();
    } else if (instrumenter instanceof Instrumenter.ForConfiguredType) {
      typeMatcher = none(); // handle below, just like when it's combined with other matchers
    } else {
      return AgentBuilder.RawMatcher.Trivial.NON_MATCHING;
    }

    if (instrumenter instanceof Instrumenter.CanShortcutTypeMatching
        && !((Instrumenter.CanShortcutTypeMatching) instrumenter).onlyMatchKnownTypes()) {
      // not taking shortcuts, so include wider hierarchical matching
      typeMatcher =
          new ElementMatcher.Junction.Disjunction(
              typeMatcher, ((Instrumenter.ForTypeHierarchy) instrumenter).hierarchyMatcher());
    }

    if (instrumenter instanceof Instrumenter.ForConfiguredType) {
      String name = ((Instrumenter.ForConfiguredType) instrumenter).configuredMatchingType();
      // only add this optional matcher when it's been configured
      if (null != name && !name.isEmpty()) {
        typeMatcher =
            new ElementMatcher.Junction.Disjunction(typeMatcher, new SingleTypeMatcher(name));
      }
    }

    if (instrumenter instanceof Instrumenter.WithTypeStructure) {
      // only perform structure matching after we've matched the type
      typeMatcher =
          new ElementMatcher.Junction.Conjunction(
              typeMatcher, ((Instrumenter.WithTypeStructure) instrumenter).structureMatcher());
    }

    ElementMatcher<ClassLoader> classLoaderMatcher = instrumenter.classLoaderMatcher();

    if (classLoaderMatcher == ANY_CLASS_LOADER && typeMatcher instanceof AgentBuilder.RawMatcher) {
      // optimization when using raw (named) type matcher with no classloader filtering
      return (AgentBuilder.RawMatcher) typeMatcher;
    }

    return new FailSafeRawMatcher(
        typeMatcher,
        classLoaderMatcher,
        "Instrumentation matcher unexpected exception - instrumentation.names="
            + instrumenter.names()
            + " instrumentation.class="
            + instrumenter.getClass().getName());
  }

  private AgentBuilder buildSingleAdvice(Instrumenter.HasAdvice instrumenter) {
    AgentBuilder.RawMatcher matcher =
        new SingleTypeMatcher(((Instrumenter.ForSingleType) instrumenter).instrumentedType());

    ignoreMatcher = isSynthetic();
    adviceBuilder =
        agentBuilder.type(matcher).and(NOT_DECORATOR_MATCHER).transform(defaultTransformers());

    instrumenter.adviceTransformations(this);

    return adviceBuilder;
  }

  static class HelperTransformer extends HelperInjector implements AgentBuilder.Transformer {
    HelperTransformer(String requestingName, String... helperClassNames) {
      super(requestingName, helperClassNames);
    }
  }

  @Override
  public void applyAdvice(ElementMatcher<? super MethodDescription> matcher, String name) {
    adviceBuilder =
        adviceBuilder.transform(
            new AgentBuilder.Transformer.ForAdvice()
                .include(Utils.getBootstrapProxy(), Utils.getAgentClassLoader())
                .withExceptionHandler(ExceptionHandlers.defaultExceptionHandler())
                .advice(not(ignoreMatcher).and(matcher), name));
  }
}
