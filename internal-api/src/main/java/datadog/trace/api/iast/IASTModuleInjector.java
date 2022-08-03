package datadog.trace.api.iast;

public class IASTModuleInjector {
  public static void inject(IASTModule module) {
    InstrumentationBridge.MODULE = module;
  }
}
