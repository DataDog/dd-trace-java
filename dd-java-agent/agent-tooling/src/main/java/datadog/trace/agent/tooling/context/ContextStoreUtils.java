package datadog.trace.agent.tooling.context;

import static datadog.trace.agent.tooling.ClassLoaderMatcher.BOOTSTRAP_CLASSLOADER;

import datadog.trace.agent.tooling.HelperInjector;
import datadog.trace.agent.tooling.Utils;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.utility.JavaModule;

final class ContextStoreUtils {

  static AgentBuilder.Transformer wrapVisitor(final AsmVisitorWrapper visitor) {
    return new AgentBuilder.Transformer() {
      @Override
      public DynamicType.Builder<?> transform(
          final DynamicType.Builder<?> builder,
          final TypeDescription typeDescription,
          final ClassLoader classLoader,
          final JavaModule module) {
        return builder.visit(visitor);
      }
    };
  }

  static Map<String, String> unpackContextStore(
      Map<ElementMatcher<ClassLoader>, Map<String, String>> matchedContextStores) {
    if (matchedContextStores.isEmpty()) {
      return Collections.emptyMap();
    } else if (matchedContextStores.size() == 1) {
      return matchedContextStores.entrySet().iterator().next().getValue();
    } else {
      Map<String, String> contextStore = new HashMap<>();
      for (Map.Entry<ElementMatcher<ClassLoader>, Map<String, String>> matcherAndStores :
          matchedContextStores.entrySet()) {
        contextStore.putAll(matcherAndStores.getValue());
      }
      return contextStore;
    }
  }

  // ---------------- DEPRECATED METHODS ONLY USED BY THE OLD FIELD-INJECTOR ----------------

  /** Get transformer that forces helper injection onto bootstrap classloader. */
  @Deprecated
  static AgentBuilder.Transformer bootstrapHelperInjector(
      final Collection<DynamicType.Unloaded<?>> types) {
    // TODO: Better to pass through the context of the Instrumenter
    return new AgentBuilder.Transformer() {
      // historical quirk - this used to be an anonymous class
      // in FieldBackedProvider and was called with getClass().getSimpleName()
      // which (unintentionally?) evaluated to an empty string - maintain this
      // behaviour
      final HelperInjector injector = HelperInjector.forDynamicTypes("", types);

      @Override
      public DynamicType.Builder<?> transform(
          final DynamicType.Builder<?> builder,
          final TypeDescription typeDescription,
          final ClassLoader classLoader,
          final JavaModule module) {
        return injector.transform(
            builder,
            typeDescription,
            // context store implementation classes will always go to the bootstrap
            BOOTSTRAP_CLASSLOADER,
            module);
      }
    };
  }

  /**
   * Note: the value here has to be inside on of the prefixes in
   * datadog.trace.agent.tooling.Constants#BOOTSTRAP_PACKAGE_PREFIXES. This ensures that 'isolating'
   * (or 'module') classloaders like jboss and osgi see injected classes. This works because we
   * instrument those classloaders to load everything inside bootstrap packages.
   */
  @Deprecated
  private static final String DYNAMIC_CLASSES_PACKAGE =
      "datadog.trace.bootstrap.instrumentation.context.";

  @Deprecated
  static String getContextStoreImplementationClassName(
      final String keyClassName, final String contextClassName) {
    return DYNAMIC_CLASSES_PACKAGE
        + FieldBackedProvider.class.getSimpleName()
        + "$ContextStore$"
        + Utils.getInnerClassName(keyClassName)
        + "$"
        + Utils.getInnerClassName(contextClassName);
  }

  @Deprecated
  static String getContextAccessorInterfaceName(
      final String keyClassName, final String contextClassName) {
    return DYNAMIC_CLASSES_PACKAGE
        + FieldBackedProvider.class.getSimpleName()
        + "$ContextAccessor$"
        + Utils.getInnerClassName(keyClassName)
        + "$"
        + Utils.getInnerClassName(contextClassName);
  }

  @Deprecated
  static String getContextFieldName(final String keyClassName) {
    return "__datadogContext$" + Utils.getInnerClassName(keyClassName);
  }

  @Deprecated
  static String getContextGetterName(final String keyClassName) {
    return "get" + getContextFieldName(keyClassName);
  }

  @Deprecated
  static String getContextSetterName(final String key) {
    return "set" + getContextFieldName(key);
  }
}
