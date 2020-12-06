package datadog.trace.agent.tooling.context;

import static datadog.trace.agent.tooling.context.ContextStoreUtils.bootstrapHelperInjector;
import static datadog.trace.agent.tooling.context.ContextStoreUtils.getContextAccessorInterfaceName;
import static datadog.trace.agent.tooling.context.ContextStoreUtils.getContextFieldName;
import static datadog.trace.agent.tooling.context.ContextStoreUtils.getContextGetterName;
import static datadog.trace.agent.tooling.context.ContextStoreUtils.getContextSetterName;
import static datadog.trace.agent.tooling.context.ContextStoreUtils.wrapVisitor;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;

/** @deprecated not used in the new field-injection strategy */
@Deprecated
final class FieldInjector {

  /** fields-accessor-interface-name -> fields-accessor-interface-dynamic-type */
  private final Map<String, DynamicType.Unloaded<?>> fieldAccessorInterfaces;

  private final ByteBuddy byteBuddy;
  private final AgentBuilder.Transformer fieldAccessorInterfacesInjector;
  private final Map<String, String> contextStore;

  FieldInjector(Map<String, String> contextStore, ByteBuddy byteBuddy) {
    this.contextStore = contextStore;
    this.byteBuddy = byteBuddy;
    this.fieldAccessorInterfaces = generateFieldAccessorInterfaces();
    this.fieldAccessorInterfacesInjector =
        bootstrapHelperInjector(fieldAccessorInterfaces.values());
  }

  AgentBuilder.Transformer fieldAccessTransformer(
      final String keyClassName, final String contextClassName) {
    return wrapVisitor(
        new FieldInjectionVisitor(
            getFieldAccessorInterface(keyClassName, contextClassName),
            getContextFieldName(keyClassName),
            getContextGetterName(keyClassName),
            getContextSetterName(keyClassName)));
  }

  TypeDescription getFieldAccessorInterface(
      final String keyClassName, final String contextClassName) {
    final DynamicType.Unloaded<?> type =
        fieldAccessorInterfaces.get(
            getContextAccessorInterfaceName(keyClassName, contextClassName));
    return null == type ? null : type.getTypeDescription();
  }

  private Map<String, DynamicType.Unloaded<?>> generateFieldAccessorInterfaces() {
    final Map<String, DynamicType.Unloaded<?>> fieldAccessorInterfaces =
        new HashMap<>(contextStore.size() * 4 / 3);
    for (final Map.Entry<String, String> entry : contextStore.entrySet()) {
      final DynamicType.Unloaded<?> type =
          makeFieldAccessorInterface(entry.getKey(), entry.getValue());
      fieldAccessorInterfaces.put(type.getTypeDescription().getName(), type);
    }
    return Collections.unmodifiableMap(fieldAccessorInterfaces);
  }

  AgentBuilder.Identified.Extendable injectIntoBootstrapClassloader(
      AgentBuilder.Identified.Extendable builder) {
    /*
     * We inject into bootstrap classloader because field accessor interfaces are needed by context
     * store implementations. Unfortunately this forces us to remove stored type checking because
     * actual classes may not be available at this point.
     */
    return builder.transform(fieldAccessorInterfacesInjector);
  }

  /**
   * Generate an interface that provides field accessor methods for given key class name and context
   * class name
   *
   * @param keyClassName key class name
   * @param contextClassName context class name
   * @return unloaded dynamic type containing generated interface
   */
  private DynamicType.Unloaded<?> makeFieldAccessorInterface(
      final String keyClassName, final String contextClassName) {
    // We are using Object class name instead of contextClassName here because this gets injected
    // onto Bootstrap classloader where context class may be unavailable
    final TypeDescription contextType = new TypeDescription.ForLoadedType(Object.class);
    return byteBuddy
        .makeInterface()
        .name(getContextAccessorInterfaceName(keyClassName, contextClassName))
        .defineMethod(getContextGetterName(keyClassName), contextType, Visibility.PUBLIC)
        .withoutCode()
        .defineMethod(getContextSetterName(keyClassName), TypeDescription.VOID, Visibility.PUBLIC)
        .withParameter(contextType, "value")
        .withoutCode()
        .make();
  }
}
