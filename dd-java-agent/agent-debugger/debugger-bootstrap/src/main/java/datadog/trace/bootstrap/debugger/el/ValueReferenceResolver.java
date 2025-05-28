package datadog.trace.bootstrap.debugger.el;

import java.util.Map;

/** Debugger EL specific value reference resolver. */
public interface ValueReferenceResolver {
  Object lookup(String name);

  Object getMember(Object target, String name);

  default void addExtension(String name, Object value) {}

  default void removeExtension(String name) {}

  default ValueReferenceResolver withExtensions(Map<String, Object> extensions) {
    return this;
  }
}
