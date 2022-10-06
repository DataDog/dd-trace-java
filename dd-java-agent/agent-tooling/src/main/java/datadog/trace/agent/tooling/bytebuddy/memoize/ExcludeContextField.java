package datadog.trace.agent.tooling.bytebuddy.memoize;

import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

final class ExcludeContextField extends ElementMatcher.Junction.ForNonNullValues<TypeDescription> {
  private final ExcludeFilter.ExcludeType excludeType;

  ExcludeContextField(ExcludeFilter.ExcludeType excludeType) {
    this.excludeType = excludeType;
  }

  @Override
  protected boolean doMatch(TypeDescription target) {
    return ExcludeFilter.exclude(excludeType, target.getName());
  }
}
