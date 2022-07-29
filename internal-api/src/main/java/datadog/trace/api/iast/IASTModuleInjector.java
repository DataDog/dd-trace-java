package datadog.trace.api.iast;

public class IASTModuleInjector {
  static void inject(IASTModule module) {
    InstrumentationBridge.MODULE = module;
  }
}
