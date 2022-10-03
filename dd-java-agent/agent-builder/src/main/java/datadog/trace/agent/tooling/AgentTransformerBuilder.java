package datadog.trace.agent.tooling;

import static datadog.trace.agent.tooling.bytebuddy.DDTransformers.defaultTransformers;
import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.ANY_CLASS_LOADER;
import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamedOneOf;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.declaresAnnotation;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.hasSuperType;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isSynthetic;
import static net.bytebuddy.matcher.ElementMatchers.none;
import static net.bytebuddy.matcher.ElementMatchers.not;

import datadog.trace.agent.tooling.bytebuddy.ExceptionHandlers;
import datadog.trace.agent.tooling.bytebuddy.matcher.FailSafeRawMatcher;
import datadog.trace.agent.tooling.bytebuddy.matcher.KnownTypesMatcher;
import datadog.trace.agent.tooling.bytebuddy.matcher.MuzzleMatcher;
import datadog.trace.agent.tooling.bytebuddy.matcher.ShouldInjectFieldsRawMatcher;
import datadog.trace.agent.tooling.bytebuddy.matcher.SingleTypeMatcher;
import datadog.trace.agent.tooling.context.FieldBackedContextInjector;
import datadog.trace.agent.tooling.context.FieldBackedContextRequestRewriter;
import datadog.trace.api.Config;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.asm.AsmVisitorWrapper;
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

  /** Associates context stores with the class-loader matchers to activate them. */
  private final Map<Map.Entry<String, String>, ElementMatcher<ClassLoader>> contextStoreInjection =
      new HashMap<>();

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
    if (Config.get().isRuntimeContextFieldInjection()) {
      applyContextStoreInjection();
    }

    return agentBuilder.installOn(instrumentation);
  }

  private AgentBuilder buildInstrumentation(final Instrumenter.Default instrumenter) {
    InstrumenterState.registerInstrumentationNames(
        instrumenter.instrumentationId(), instrumenter.names());

    ignoreMatcher = instrumenter.methodIgnoreMatcher();
    adviceBuilder =
        agentBuilder
            .type(typeMatcher(instrumenter))
            .and(NOT_DECORATOR_MATCHER)
            .and(new MuzzleMatcher(instrumenter))
            .transform(defaultTransformers());

    String[] helperClassNames = instrumenter.helperClassNames();
    if (helperClassNames.length > 0) {
      adviceBuilder =
          adviceBuilder.transform(
              new HelperTransformer(instrumenter.getClass().getSimpleName(), helperClassNames));
    }

    Map<String, String> contextStore = instrumenter.contextStore();
    if (!contextStore.isEmpty()) {
      // rewrite context store access to call FieldBackedContextStores with assigned store-id
      adviceBuilder =
          adviceBuilder.transform(
              wrapVisitor(
                  new FieldBackedContextRequestRewriter(contextStore, instrumenter.name())));

      registerContextStoreInjection(contextStore, instrumenter);
    }

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
                    JavaModule module,
                    ProtectionDomain pd) {
                  return customTransformer.transform(
                      builder, typeDescription, classLoader, module, pd);
                }
              });
    }

    instrumenter.adviceTransformations(this);

    return adviceBuilder;
  }

  private AgentBuilder.RawMatcher typeMatcher(Instrumenter.Default instrumenter) {
    ElementMatcher<? super TypeDescription> typeMatcher;
    String hierarchyHint = null;

    if (instrumenter instanceof Instrumenter.ForSingleType) {
      String name = ((Instrumenter.ForSingleType) instrumenter).instrumentedType();
      typeMatcher = new SingleTypeMatcher(name);
    } else if (instrumenter instanceof Instrumenter.ForKnownTypes) {
      String[] names = ((Instrumenter.ForKnownTypes) instrumenter).knownMatchingTypes();
      typeMatcher = new KnownTypesMatcher(names);
    } else if (instrumenter instanceof Instrumenter.ForTypeHierarchy) {
      typeMatcher = ((Instrumenter.ForTypeHierarchy) instrumenter).hierarchyMatcher();
      hierarchyHint = ((Instrumenter.ForTypeHierarchy) instrumenter).hierarchyMarkerType();
    } else if (instrumenter instanceof Instrumenter.ForConfiguredType) {
      typeMatcher = none(); // handle below, just like when it's combined with other matchers
    } else if (instrumenter instanceof Instrumenter.ForCallSite) {
      typeMatcher = ((Instrumenter.ForCallSite) instrumenter).callerType();
    } else {
      return AgentBuilder.RawMatcher.Trivial.NON_MATCHING;
    }

    if (instrumenter instanceof Instrumenter.CanShortcutTypeMatching
        && !((Instrumenter.CanShortcutTypeMatching) instrumenter).onlyMatchKnownTypes()) {
      // not taking shortcuts, so include wider hierarchical matching
      typeMatcher =
          new ElementMatcher.Junction.Disjunction(
              typeMatcher, ((Instrumenter.ForTypeHierarchy) instrumenter).hierarchyMatcher());
      hierarchyHint = ((Instrumenter.ForTypeHierarchy) instrumenter).hierarchyMarkerType();
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

  private static AgentBuilder.Transformer wrapVisitor(final AsmVisitorWrapper visitor) {
    return new AgentBuilder.Transformer() {
      @Override
      public DynamicType.Builder<?> transform(
          final DynamicType.Builder<?> builder,
          final TypeDescription typeDescription,
          final ClassLoader classLoader,
          final JavaModule module,
          final ProtectionDomain pd) {
        return builder.visit(visitor);
      }
    };
  }

  private static ElementMatcher<ClassLoader> requireBoth(
      ElementMatcher<ClassLoader> lhs, ElementMatcher<ClassLoader> rhs) {
    if (ANY_CLASS_LOADER == lhs) {
      return rhs;
    } else if (ANY_CLASS_LOADER == rhs) {
      return lhs;
    } else {
      return new ElementMatcher.Junction.Conjunction<>(lhs, rhs);
    }
  }

  /** Tracks which class-loader matchers are associated with each store request. */
  private void registerContextStoreInjection(
      Map<String, String> contextStore, Instrumenter.Default instrumenter) {
    ElementMatcher<ClassLoader> activation;

    if (instrumenter instanceof Instrumenter.ForBootstrap) {
      activation = ANY_CLASS_LOADER;
    } else if (instrumenter instanceof Instrumenter.ForTypeHierarchy) {
      String hierarchyHint = ((Instrumenter.ForTypeHierarchy) instrumenter).hierarchyMarkerType();
      activation = null != hierarchyHint ? hasClassNamed(hierarchyHint) : ANY_CLASS_LOADER;
    } else if (instrumenter instanceof Instrumenter.ForSingleType) {
      activation = hasClassNamed(((Instrumenter.ForSingleType) instrumenter).instrumentedType());
    } else if (instrumenter instanceof Instrumenter.ForKnownTypes) {
      activation =
          hasClassNamedOneOf(((Instrumenter.ForKnownTypes) instrumenter).knownMatchingTypes());
    } else {
      activation = ANY_CLASS_LOADER;
    }

    activation = requireBoth(activation, instrumenter.classLoaderMatcher());

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

  /** Applies context store injection guarded by the associated class-loader matchers. */
  private void applyContextStoreInjection() {
    for (Map.Entry<Map.Entry<String, String>, ElementMatcher<ClassLoader>> injection :
        contextStoreInjection.entrySet()) {
      String keyClassName = injection.getKey().getKey();
      String contextClassName = injection.getKey().getValue();
      agentBuilder =
          agentBuilder
              .type(hasSuperType(named(keyClassName)), injection.getValue())
              .and(new ShouldInjectFieldsRawMatcher(keyClassName, contextClassName))
              .and(AgentTransformerBuilder.NOT_DECORATOR_MATCHER)
              .transform(
                  wrapVisitor(new FieldBackedContextInjector(keyClassName, contextClassName)));
    }
  }
}
