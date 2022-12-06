package datadog.trace.bootstrap.debugger.el;

import java.util.Map;

/**
 * Debugger EL specific value reference path resolver.
 *
 * <p>A reference path has the following format:<br>
 * <br>
 * {@code ref = <prefix><name>(.<field>)*}}<br>
 * <br>
 * where<hr> {@code <prefix> : @|^|#|.}
 *
 * <p>{@code <name> : [a-zA-Z][a-zA-Z_0-9]*}
 *
 * <p>{@code <field> : [a-zA-Z][a-zA-Z_0-9]*}
 *
 * <p><hr> The prefix has the following meaning:
 *
 * <ul>
 *   <li><b>@</b> = synthetic (eg. @return or @duration)
 *   <li><b>^</b> = method argument
 *   <li><b>#</b> = local variable
 *   <li><b>.</b> = top level field
 * </ul>
 */
public interface ValueReferenceResolver {

  Object resolve(String path);

  default ValueReferenceResolver withExtensions(Map<String, Object> extensions) {
    return this;
  }
}
