package datadog.trace.instrumentation.liberty20;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;

import datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers;
import net.bytebuddy.description.NamedElement;
import net.bytebuddy.description.type.TypeDefinition;
import net.bytebuddy.matcher.ElementMatcher;

public class ArrayOfTypeMatcher<T extends TypeDefinition>
    extends ElementMatcher.Junction.ForNonNullValues<T> {
  private final NameMatchers.Named<NamedElement> componentMatcher;

  public ArrayOfTypeMatcher(String typeName) {
    this.componentMatcher = named(typeName);
  }

  protected boolean doMatch(T target) {
    return target.isArray() && componentMatcher.matches(target.getComponentType());
  }

  @Override
  public String toString() {
    return "isArrayOfType(" + componentMatcher + ")";
  }
}
