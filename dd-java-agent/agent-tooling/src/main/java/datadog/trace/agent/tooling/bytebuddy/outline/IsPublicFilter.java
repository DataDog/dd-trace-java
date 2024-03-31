package datadog.trace.agent.tooling.bytebuddy.outline;

import datadog.trace.agent.tooling.bytebuddy.ClassCodeFilter;
import datadog.trace.api.InstrumenterConfig;

/** Compact filter that records public types. */
final class IsPublicFilter extends ClassCodeFilter {
  IsPublicFilter() {
    super(InstrumenterConfig.get().getResolverVisibilitySize());
  }
}
