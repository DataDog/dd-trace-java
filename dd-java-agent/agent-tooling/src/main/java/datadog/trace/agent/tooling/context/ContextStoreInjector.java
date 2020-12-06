package datadog.trace.agent.tooling.context;

import static datadog.trace.agent.tooling.context.ContextStoreUtils.bootstrapHelperInjector;
import static datadog.trace.agent.tooling.context.ContextStoreUtils.getContextGetterName;
import static datadog.trace.agent.tooling.context.ContextStoreUtils.getContextSetterName;
import static datadog.trace.agent.tooling.context.ContextStoreUtils.getContextStoreImplementationClassName;
import static datadog.trace.agent.tooling.context.ContextStoreUtils.wrapVisitor;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.modifier.TypeManifestation;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.dynamic.DynamicType;

/** @deprecated not used in the new field-injection strategy */
@Deprecated
final class ContextStoreInjector {

  private final ByteBuddy byteBuddy;
  private final Map<String, String> contextStore;
  private final String instrumenterName;
  private final FieldInjector fieldInjector;

  /** context-store-type-name -> context-store-type-name-dynamic-type */
  private final Map<String, DynamicType.Unloaded<?>> contextStoreImplementations;

  private final AgentBuilder.Transformer contextStoreImplementationsInjector;

  ContextStoreInjector(
      Map<String, String> contextStore,
      ByteBuddy byteBuddy,
      FieldInjector fieldInjector,
      String instrumenterName) {
    this.contextStore = contextStore;
    this.byteBuddy = byteBuddy;
    this.fieldInjector = fieldInjector;
    this.instrumenterName = instrumenterName;
    this.contextStoreImplementations = generateContextStoreImplementationClasses();
    this.contextStoreImplementationsInjector =
        bootstrapHelperInjector(contextStoreImplementations.values());
  }

  AgentBuilder.Transformer readTransformer() {
    return wrapVisitor(
        new ContextStoreReadsRewritingVisitor(
            contextStoreImplementations, contextStore, instrumenterName));
  }

  AgentBuilder.Identified.Extendable injectIntoBootstrapClassloader(
      AgentBuilder.Identified.Extendable builder) {

    /*
     * We inject context store implementation into bootstrap classloader because same implementation
     * may be used by different instrumentations and it has to use same static map in case of
     * fallback to map-backed storage.
     */
    return builder.transform(contextStoreImplementationsInjector);
  }

  private Map<String, DynamicType.Unloaded<?>> generateContextStoreImplementationClasses() {
    final Map<String, DynamicType.Unloaded<?>> contextStoreImplementations =
        new HashMap<>(contextStore.size() * 4 / 3);
    for (final Map.Entry<String, String> entry : contextStore.entrySet()) {
      final DynamicType.Unloaded<?> type =
          makeContextStoreImplementationClass(entry.getKey(), entry.getValue());
      contextStoreImplementations.put(type.getTypeDescription().getName(), type);
    }
    return Collections.unmodifiableMap(contextStoreImplementations);
  }

  /**
   * Generate an 'implementation' of a context store classfor given key class name and context class
   * name
   *
   * @param keyClassName key class name
   * @param contextClassName context class name
   * @return unloaded dynamic type containing generated class
   */
  private DynamicType.Unloaded<?> makeContextStoreImplementationClass(
      final String keyClassName, final String contextClassName) {
    return byteBuddy
        .rebase(ContextStoreImplementationTemplate.class)
        .modifiers(Visibility.PUBLIC, TypeManifestation.FINAL)
        .name(getContextStoreImplementationClassName(keyClassName, contextClassName))
        .visit(getContextStoreImplementationVisitor(keyClassName, contextClassName))
        .make();
  }

  /**
   * Returns a visitor that 'fills in' missing methods into concrete implementation of
   * ContextStoreImplementationTemplate for given key class name and context class name
   *
   * @param keyClassName key class name
   * @param contextClassName context class name
   * @return visitor that adds implementation for methods that need to be generated
   */
  private AsmVisitorWrapper getContextStoreImplementationVisitor(
      final String keyClassName, final String contextClassName) {
    return new ContextStoreImplementationVisitor(
        getContextSetterName(keyClassName),
        getContextGetterName(keyClassName),
        fieldInjector.getFieldAccessorInterface(keyClassName, contextClassName));
  }
}
