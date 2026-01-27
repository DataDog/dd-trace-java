package datadog.trace.agent.tooling;

import static java.util.Collections.disjoint;

import java.util.Set;

@FunctionalInterface
public interface InstrumenterModuleFilter {
  InstrumenterModuleFilter ALL_MODULES = (name, systems, excludeProvider) -> true;

  static InstrumenterModuleFilter forTargetSystemsOrExcludeProvider(
      final Set<InstrumenterModule.TargetSystem> enabledSystems) {
    return (instrumenterModuleName, targetSystems, isExcludeProvider)
        -> isExcludeProvider || !disjoint(enabledSystems, targetSystems);
  }

  boolean test(
      String instrumenterModuleName,
      Set<InstrumenterModule.TargetSystem> targetSystems,
      boolean isExcludeProvider);
}
