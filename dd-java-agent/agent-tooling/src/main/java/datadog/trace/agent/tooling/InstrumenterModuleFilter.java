package datadog.trace.agent.tooling;

import static java.util.Collections.disjoint;

import java.util.Set;

@FunctionalInterface
public interface InstrumenterModuleFilter {
  InstrumenterModuleFilter ALL_MODULES = (name, systems, excludeProvider) -> true;

  static InstrumenterModuleFilter forTargetSystemsOrExcludeProvider(
      final Set<InstrumenterModule.TargetSystem> targetSystems) {
    return (name, systems, excludeProvider) -> excludeProvider || !disjoint(targetSystems, systems);
  }

  boolean test(
      String instrumenterModuleName,
      Set<InstrumenterModule.TargetSystem> targetSystems,
      boolean excludeProvider);
}
