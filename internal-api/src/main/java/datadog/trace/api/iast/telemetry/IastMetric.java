package datadog.trace.api.iast.telemetry;

import static datadog.trace.api.iast.telemetry.IastMetric.Scope.GLOBAL;
import static datadog.trace.api.iast.telemetry.IastMetric.Scope.REQUEST;
import static datadog.trace.api.iast.telemetry.IastMetric.Tags.SOURCE_TYPE;
import static datadog.trace.api.iast.telemetry.IastMetric.Tags.VULNERABILITY_TYPE;
import static datadog.trace.api.iast.telemetry.Verbosity.DEBUG;
import static datadog.trace.api.iast.telemetry.Verbosity.INFORMATION;
import static datadog.trace.api.iast.telemetry.Verbosity.MANDATORY;

public enum IastMetric {
  INSTRUMENTED_PROPAGATION("instrumented.propagation", true, GLOBAL, MANDATORY),
  INSTRUMENTED_SOURCE("instrumented.source", true, GLOBAL, MANDATORY, SOURCE_TYPE),
  INSTRUMENTED_SINK("instrumented.sink", true, GLOBAL, MANDATORY, VULNERABILITY_TYPE),
  EXECUTED_PROPAGATION("executed.propagation", true, REQUEST, DEBUG),
  EXECUTED_SOURCE("executed.source", true, REQUEST, INFORMATION, SOURCE_TYPE),
  EXECUTED_SINK("executed.sink", true, REQUEST, INFORMATION, VULNERABILITY_TYPE),
  EXECUTED_TAINTED("executed.tainted", true, REQUEST, DEBUG),
  REQUEST_TAINTED("request.tainted", true, REQUEST, INFORMATION),
  TAINTED_FLAT_MODE("tainted.flat.mode", false, REQUEST, INFORMATION);

  private final String name;
  private final boolean common;
  private final Scope scope;
  private final String tag;

  private final Verbosity verbosity;

  IastMetric(
      final String name, final boolean common, final Scope scope, final Verbosity verbosity) {
    this(name, common, scope, verbosity, null);
  }

  IastMetric(
      final String name,
      final boolean common,
      final Scope scope,
      final Verbosity verbosity,
      final String tag) {
    this.name = name;
    this.common = common;
    this.scope = scope;
    this.verbosity = verbosity;
    this.tag = tag;
  }

  public String getName() {
    return name;
  }

  public String getType() {
    return "COUNT";
  }

  public boolean isCommon() {
    return common;
  }

  public Scope getScope() {
    return scope;
  }

  public String getTag() {
    return tag;
  }

  public boolean isEnabled(final Verbosity verbosity) {
    return verbosity.isEnabled(this.verbosity);
  }

  public abstract static class Tags {

    private Tags() {}

    public static final String VULNERABILITY_TYPE = "vulnerability_type";
    public static final String SOURCE_TYPE = "source_type";
  }

  public enum Scope {
    GLOBAL,
    REQUEST
  }
}
