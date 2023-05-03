package datadog.trace.agent.tooling.context;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.declaresContextField;

import datadog.trace.bootstrap.FieldBackedContextAccessor;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class FieldBackedContextMatcher {
  private static final Logger log = LoggerFactory.getLogger(FieldBackedContextMatcher.class);

  private final String keyType;
  private final String valueType;

  private final ElementMatcher<TypeDescription> shouldInjectContextField;

  public FieldBackedContextMatcher(String keyType, String valueType) {
    this.keyType = keyType;
    this.valueType = valueType;

    this.shouldInjectContextField = declaresContextField(keyType, valueType);
  }

  public boolean matches(TypeDescription target, Class<?> classBeingRedefined) {

    /*
     * The idea here is that we can add fields if class is just being loaded
     * (classBeingRedefined == null) and we have to add same fields again if
     * the class we added fields to before is being transformed again.
     */
    boolean canInject =
        classBeingRedefined == null || implementsContextAccessor(classBeingRedefined);

    boolean injectContextField = canInject && shouldInjectContextField.matches(target);

    if (log.isDebugEnabled()) {
      if (injectContextField) {
        log.debug(
            "Added context-store field - instrumentation.target.class={} instrumentation.target.context={}->{}",
            target.getName(),
            keyType,
            valueType);
      } else if (!canInject && shouldInjectContextField.matches(target)) {
        // must be a re-define of a class that we weren't able to field-inject on startup
        // - make sure we'd have field-injected (if we'd had the chance) before reporting
        log.debug(
            "Failed to add context-store field - instrumentation.target.class={} instrumentation.target.context={}->{}",
            target.getName(),
            keyType,
            valueType);
      }
    }

    return injectContextField;
  }

  public String describe() {
    return "contextStore(" + keyType + "," + valueType + ")";
  }

  /**
   * Assumes that {@link Class#getInterfaces()} returns list of interfaces defined immediately on a
   * given class, not inherited from its parents.
   */
  private static boolean implementsContextAccessor(Class<?> classBeingRedefined) {
    for (Class<?> intf : classBeingRedefined.getInterfaces()) {
      if (FieldBackedContextAccessor.class.equals(intf)) {
        return true;
      }
    }
    return false;
  }
}
