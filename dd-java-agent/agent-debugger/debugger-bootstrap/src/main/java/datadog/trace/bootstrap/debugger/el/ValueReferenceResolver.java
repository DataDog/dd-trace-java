package datadog.trace.bootstrap.debugger.el;

import datadog.trace.bootstrap.debugger.CapturedContext.CapturedValue;
import java.util.Map;

/** Debugger EL specific value reference resolver. */
public interface ValueReferenceResolver {
  CapturedValue lookup(String name);

  CapturedValue getMember(Object target, String name);

  default void addExtension(String name, CapturedValue value) {}

  default void removeExtension(String name) {}

  default ValueReferenceResolver withExtensions(Map<String, CapturedValue> extensions) {
    return this;
  }
}
