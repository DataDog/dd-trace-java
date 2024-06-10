package datadog.trace.bootstrap.otel.instrumentation.internal;

public final class EnduserConfig {
  private final boolean idEnabled;
  private final boolean roleEnabled;
  private final boolean scopeEnabled;

  EnduserConfig(InstrumentationConfig instrumentationConfig) {
    this.idEnabled =
        instrumentationConfig.getBoolean("otel.instrumentation.common.enduser.id.enabled", false);
    this.roleEnabled =
        instrumentationConfig.getBoolean("otel.instrumentation.common.enduser.role.enabled", false);
    this.scopeEnabled =
        instrumentationConfig.getBoolean(
            "otel.instrumentation.common.enduser.scope.enabled", false);
  }

  public boolean isAnyEnabled() {
    return this.idEnabled || this.roleEnabled || this.scopeEnabled;
  }

  public boolean isIdEnabled() {
    return this.idEnabled;
  }

  public boolean isRoleEnabled() {
    return this.roleEnabled;
  }

  public boolean isScopeEnabled() {
    return this.scopeEnabled;
  }
}
