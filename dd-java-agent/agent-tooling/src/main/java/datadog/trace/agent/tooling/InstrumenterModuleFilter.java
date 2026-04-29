package datadog.trace.agent.tooling;

import static java.util.Collections.disjoint;

import java.util.Set;

@FunctionalInterface
public interface InstrumenterModuleFilter {
  InstrumenterModuleFilter ALL_MODULES =
      (name, systems, excludeProvider, isJavaModuleOpenProvider) -> true;

  static InstrumenterModuleFilter forTargetSystemsOrNeedToEarlyLoad(
      final Set<InstrumenterModule.TargetSystem> enabledSystems) {
    return (instrumenterModuleName, targetSystems, isExcludeProvider, isJavaModuleOpenProvider) ->
        isExcludeProvider || isJavaModuleOpenProvider || !disjoint(enabledSystems, targetSystems);
  }

  boolean test(
      String instrumenterModuleName,
      Set<InstrumenterModule.TargetSystem> targetSystems,
      boolean isExcludeProvider,
      boolean isJavaModuleOpenProvider);
}
