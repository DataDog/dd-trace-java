package datadog.trace.agent.tooling;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.ANY_CLASS_LOADER;
import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamedOneOf;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.declaresAnnotation;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.not;

import java.security.ProtectionDomain;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.utility.JavaModule;

abstract class AbstractTransformerBuilder
    implements Instrumenter.TransformerBuilder,
        Instrumenter.TypeTransformer,
        Instrumenter.MethodTransformer {

  // Added here instead of byte-buddy's ignores because it's relatively
  // expensive. https://github.com/DataDog/dd-trace-java/pull/1045
  protected static final ElementMatcher.Junction<TypeDescription> NOT_DECORATOR_MATCHER =
      not(
          declaresAnnotation(
              namedOneOf("javax.decorator.Decorator", "jakarta.decorator.Decorator")));

  /** Associates context stores with the class-loader matchers to activate them. */
  private final Map<Map.Entry<String, String>, ElementMatcher<ClassLoader>> contextStoreInjection =
      new HashMap<>();

  @Override
  public final void applyInstrumentation(Instrumenter.HasAdvice instrumenter) {
    if (instrumenter instanceof Instrumenter.Default) {
      buildInstrumentation((Instrumenter.Default) instrumenter);
    } else if (instrumenter instanceof Instrumenter.ForSingleType) {
      buildSingleAdvice((Instrumenter.ForSingleType) instrumenter); // for testing purposes
    } else {
      throw new IllegalArgumentException("Unexpected Instrumenter type");
    }
  }

  protected abstract void buildInstrumentation(Instrumenter.Default instrumenter);

  protected abstract void buildSingleAdvice(Instrumenter.ForSingleType instrumenter);

  protected static final class VisitingTransformer implements AgentBuilder.Transformer {
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

  protected static final class HelperTransformer extends HelperInjector
      implements AgentBuilder.Transformer {
    HelperTransformer(String requestingName, String... helperClassNames) {
      super(requestingName, helperClassNames);
    }
  }

  protected static ElementMatcher<ClassLoader> requireBoth(
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
  protected final void registerContextStoreInjection(
      Instrumenter.Default instrumenter, Map<String, String> contextStore) {
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

  /** Counts the number of distinct context store injections registered with this builder. */
  protected final int contextStoreCount() {
    return contextStoreInjection.size();
  }

  /** Applies each context store injection, guarded by the associated class-loader matcher. */
  protected final void applyContextStoreInjection() {
    contextStoreInjection.forEach(this::applyContextStoreInjection);
  }

  /** Arranges for a context value field to be injected into types extending the context key. */
  protected abstract void applyContextStoreInjection(
      Map.Entry<String, String> contextStore, ElementMatcher<ClassLoader> activation);
}
