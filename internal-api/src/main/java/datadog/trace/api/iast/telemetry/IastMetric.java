package datadog.trace.api.iast.telemetry;

import static datadog.trace.api.iast.SourceTypes.REQUEST_BODY_STRING;
import static datadog.trace.api.iast.SourceTypes.REQUEST_COOKIE_NAME_STRING;
import static datadog.trace.api.iast.SourceTypes.REQUEST_COOKIE_VALUE_STRING;
import static datadog.trace.api.iast.SourceTypes.REQUEST_HEADER_NAME_STRING;
import static datadog.trace.api.iast.SourceTypes.REQUEST_HEADER_VALUE_STRING;
import static datadog.trace.api.iast.SourceTypes.REQUEST_MATRIX_PARAMETER_STRING;
import static datadog.trace.api.iast.SourceTypes.REQUEST_PARAMETER_NAME_STRING;
import static datadog.trace.api.iast.SourceTypes.REQUEST_PARAMETER_VALUE_STRING;
import static datadog.trace.api.iast.SourceTypes.REQUEST_PATH_PARAMETER_STRING;
import static datadog.trace.api.iast.SourceTypes.REQUEST_QUERY_STRING;
import static datadog.trace.api.iast.VulnerabilityTypes.COMMAND_INJECTION;
import static datadog.trace.api.iast.VulnerabilityTypes.INSECURE_COOKIE;
import static datadog.trace.api.iast.VulnerabilityTypes.LDAP_INJECTION;
import static datadog.trace.api.iast.VulnerabilityTypes.PATH_TRAVERSAL;
import static datadog.trace.api.iast.VulnerabilityTypes.SQL_INJECTION;
import static datadog.trace.api.iast.VulnerabilityTypes.SSRF;
import static datadog.trace.api.iast.VulnerabilityTypes.WEAK_CIPHER;
import static datadog.trace.api.iast.VulnerabilityTypes.WEAK_HASH;
import static datadog.trace.api.iast.VulnerabilityTypes.WEAK_RANDOMNESS;
import static datadog.trace.api.iast.telemetry.IastMetric.Scope.GLOBAL;
import static datadog.trace.api.iast.telemetry.IastMetric.Scope.REQUEST;
import static datadog.trace.api.iast.telemetry.IastMetric.Tags.SOURCE_TYPE;
import static datadog.trace.api.iast.telemetry.IastMetric.Tags.VULNERABILITY_TYPE;
import static datadog.trace.api.iast.telemetry.Verbosity.DEBUG;
import static datadog.trace.api.iast.telemetry.Verbosity.INFORMATION;
import static datadog.trace.api.iast.telemetry.Verbosity.MANDATORY;

import de.thetaphi.forbiddenapis.SuppressForbidden;
import javax.annotation.Nonnull;

public enum IastMetric {
  INSTRUMENTED_PROPAGATION(MetricNames.INSTRUMENTED_PROPAGATION, true, GLOBAL, MANDATORY),
  INSTRUMENTED_SOURCE_REQUEST_PARAMETER_NAME(
      MetricNames.INSTRUMENTED_SOURCE,
      true,
      GLOBAL,
      MANDATORY,
      SOURCE_TYPE,
      REQUEST_PARAMETER_NAME_STRING),
  INSTRUMENTED_SOURCE_REQUEST_PARAMETER_VALUE(
      MetricNames.INSTRUMENTED_SOURCE,
      true,
      GLOBAL,
      MANDATORY,
      SOURCE_TYPE,
      REQUEST_PARAMETER_VALUE_STRING),
  INSTRUMENTED_SOURCE_REQUEST_HEADER_NAME(
      MetricNames.INSTRUMENTED_SOURCE,
      true,
      GLOBAL,
      MANDATORY,
      SOURCE_TYPE,
      REQUEST_HEADER_NAME_STRING),
  INSTRUMENTED_SOURCE_REQUEST_HEADER_VALUE(
      MetricNames.INSTRUMENTED_SOURCE,
      true,
      GLOBAL,
      MANDATORY,
      SOURCE_TYPE,
      REQUEST_HEADER_VALUE_STRING),
  INSTRUMENTED_SOURCE_REQUEST_COOKIE_NAME(
      MetricNames.INSTRUMENTED_SOURCE,
      true,
      GLOBAL,
      MANDATORY,
      SOURCE_TYPE,
      REQUEST_COOKIE_NAME_STRING),
  INSTRUMENTED_SOURCE_REQUEST_COOKIE_VALUE(
      MetricNames.INSTRUMENTED_SOURCE,
      true,
      GLOBAL,
      MANDATORY,
      SOURCE_TYPE,
      REQUEST_COOKIE_VALUE_STRING),
  INSTRUMENTED_SOURCE_REQUEST_REQUEST_BODY(
      MetricNames.INSTRUMENTED_SOURCE, true, GLOBAL, MANDATORY, SOURCE_TYPE, REQUEST_BODY_STRING),
  INSTRUMENTED_SOURCE_REQUEST_QUERY(
      MetricNames.INSTRUMENTED_SOURCE, true, GLOBAL, MANDATORY, SOURCE_TYPE, REQUEST_QUERY_STRING),
  INSTRUMENTED_SOURCE_REQUEST_PATH_PARAMETER(
      MetricNames.INSTRUMENTED_SOURCE,
      true,
      GLOBAL,
      MANDATORY,
      SOURCE_TYPE,
      REQUEST_PATH_PARAMETER_STRING),
  INSTRUMENTED_SOURCE_REQUEST_MATRIX_PARAMETER(
      MetricNames.INSTRUMENTED_SOURCE,
      true,
      GLOBAL,
      MANDATORY,
      SOURCE_TYPE,
      REQUEST_MATRIX_PARAMETER_STRING),

  INSTRUMENTED_SINK_WEAK_CIPHER(
      MetricNames.INSTRUMENTED_SINK, true, GLOBAL, MANDATORY, VULNERABILITY_TYPE, WEAK_CIPHER),
  INSTRUMENTED_SINK_WEAK_HASH(
      MetricNames.INSTRUMENTED_SINK, true, GLOBAL, MANDATORY, VULNERABILITY_TYPE, WEAK_HASH),
  INSTRUMENTED_SINK_SQL_INJECTION(
      MetricNames.INSTRUMENTED_SINK, true, GLOBAL, MANDATORY, VULNERABILITY_TYPE, SQL_INJECTION),
  INSTRUMENTED_SINK_COMMAND_INJECTION(
      MetricNames.INSTRUMENTED_SINK,
      true,
      GLOBAL,
      MANDATORY,
      VULNERABILITY_TYPE,
      COMMAND_INJECTION),
  INSTRUMENTED_SINK_PATH_TRAVERSAL(
      MetricNames.INSTRUMENTED_SINK, true, GLOBAL, MANDATORY, VULNERABILITY_TYPE, PATH_TRAVERSAL),
  INSTRUMENTED_SINK_LDAP_INJECTION(
      MetricNames.INSTRUMENTED_SINK, true, GLOBAL, MANDATORY, VULNERABILITY_TYPE, LDAP_INJECTION),
  INSTRUMENTED_SINK_SSRF(
      MetricNames.INSTRUMENTED_SINK, true, GLOBAL, MANDATORY, VULNERABILITY_TYPE, SSRF),
  INSTRUMENTED_SINK_INSECURE_COOKIE(
      MetricNames.INSTRUMENTED_SINK, true, GLOBAL, MANDATORY, VULNERABILITY_TYPE, INSECURE_COOKIE),
  INSTRUMENTED_SINK_UNVALIDATED_REDIRECT(
      MetricNames.INSTRUMENTED_SINK, true, GLOBAL, MANDATORY, VULNERABILITY_TYPE, INSECURE_COOKIE),
  INSTRUMENTED_SINK_WEAK_RANDOMNESS(
      MetricNames.INSTRUMENTED_SINK, true, GLOBAL, MANDATORY, VULNERABILITY_TYPE, WEAK_RANDOMNESS),
  EXECUTED_PROPAGATION(MetricNames.EXECUTED_PROPAGATION, true, REQUEST, DEBUG),
  EXECUTED_SOURCE_REQUEST_PARAMETER_NAME(
      MetricNames.EXECUTED_SOURCE,
      true,
      REQUEST,
      INFORMATION,
      SOURCE_TYPE,
      REQUEST_PARAMETER_NAME_STRING),
  EXECUTED_SOURCE_REQUEST_PARAMETER_VALUE(
      MetricNames.EXECUTED_SOURCE,
      true,
      REQUEST,
      INFORMATION,
      SOURCE_TYPE,
      REQUEST_PARAMETER_VALUE_STRING),
  EXECUTED_SOURCE_REQUEST_HEADER_NAME(
      MetricNames.EXECUTED_SOURCE,
      true,
      REQUEST,
      INFORMATION,
      SOURCE_TYPE,
      REQUEST_HEADER_NAME_STRING),
  EXECUTED_SOURCE_REQUEST_HEADER_VALUE(
      MetricNames.EXECUTED_SOURCE,
      true,
      REQUEST,
      INFORMATION,
      SOURCE_TYPE,
      REQUEST_HEADER_VALUE_STRING),
  EXECUTED_SOURCE_REQUEST_COOKIE_NAME(
      MetricNames.EXECUTED_SOURCE,
      true,
      REQUEST,
      INFORMATION,
      SOURCE_TYPE,
      REQUEST_COOKIE_NAME_STRING),
  EXECUTED_SOURCE_REQUEST_COOKIE_VALUE(
      MetricNames.EXECUTED_SOURCE,
      true,
      REQUEST,
      INFORMATION,
      SOURCE_TYPE,
      REQUEST_COOKIE_VALUE_STRING),
  EXECUTED_SOURCE_REQUEST_REQUEST_BODY(
      MetricNames.EXECUTED_SOURCE, true, REQUEST, INFORMATION, SOURCE_TYPE, REQUEST_BODY_STRING),
  EXECUTED_SOURCE_REQUEST_QUERY(
      MetricNames.EXECUTED_SOURCE, true, REQUEST, INFORMATION, SOURCE_TYPE, REQUEST_QUERY_STRING),
  EXECUTED_SOURCE_REQUEST_PATH_PARAMETER(
      MetricNames.EXECUTED_SOURCE,
      true,
      REQUEST,
      INFORMATION,
      SOURCE_TYPE,
      REQUEST_PATH_PARAMETER_STRING),
  EXECUTED_SOURCE_REQUEST_MATRIX_PARAMETER(
      MetricNames.EXECUTED_SOURCE,
      true,
      REQUEST,
      INFORMATION,
      SOURCE_TYPE,
      REQUEST_MATRIX_PARAMETER_STRING),

  EXECUTED_SINK_WEAK_CIPHER(
      MetricNames.EXECUTED_SINK, true, REQUEST, INFORMATION, VULNERABILITY_TYPE, WEAK_CIPHER),
  EXECUTED_SINK_WEAK_HASH(
      MetricNames.EXECUTED_SINK, true, REQUEST, INFORMATION, VULNERABILITY_TYPE, WEAK_HASH),
  EXECUTED_SINK_SQL_INJECTION(
      MetricNames.EXECUTED_SINK, true, REQUEST, INFORMATION, VULNERABILITY_TYPE, SQL_INJECTION),
  EXECUTED_SINK_COMMAND_INJECTION(
      MetricNames.EXECUTED_SINK, true, REQUEST, INFORMATION, VULNERABILITY_TYPE, COMMAND_INJECTION),
  EXECUTED_SINK_PATH_TRAVERSAL(
      MetricNames.EXECUTED_SINK, true, REQUEST, INFORMATION, VULNERABILITY_TYPE, PATH_TRAVERSAL),
  EXECUTED_SINK_LDAP_INJECTION(
      MetricNames.EXECUTED_SINK, true, REQUEST, INFORMATION, VULNERABILITY_TYPE, LDAP_INJECTION),
  EXECUTED_SINK_SSRF(
      MetricNames.EXECUTED_SINK, true, REQUEST, INFORMATION, VULNERABILITY_TYPE, SSRF),
  EXECUTED_SINK_INSECURE_COOKIE(
      MetricNames.EXECUTED_SINK, true, REQUEST, INFORMATION, VULNERABILITY_TYPE, INSECURE_COOKIE),
  EXECUTED_SINK_UNVALIDATED_REDIRECT(
      MetricNames.EXECUTED_SINK, true, REQUEST, INFORMATION, VULNERABILITY_TYPE, INSECURE_COOKIE),
  EXECUTED_SINK_WEAK_RANDOMNESS(
      MetricNames.EXECUTED_SINK, true, REQUEST, INFORMATION, VULNERABILITY_TYPE, WEAK_RANDOMNESS),
  EXECUTED_TAINTED(MetricNames.EXECUTED_TAINTED, true, REQUEST, DEBUG),
  REQUEST_TAINTED(MetricNames.REQUEST_TAINTED, true, REQUEST, INFORMATION),
  TAINTED_FLAT_MODE(MetricNames.TAINTED_FLAT_MODE, false, REQUEST, INFORMATION);

  private final String name;
  private final boolean common;
  private final Scope scope;
  private final String tagName;
  private final String tagValue;
  private final Verbosity verbosity;

  IastMetric(
      final String name, final boolean common, final Scope scope, final Verbosity verbosity) {
    this(name, common, scope, verbosity, null, null);
  }

  IastMetric(
      final String name,
      final boolean common,
      final Scope scope,
      final Verbosity verbosity,
      final String tagName,
      final String tagValue) {
    this.name = name;
    this.common = common;
    this.scope = scope;
    this.verbosity = verbosity;
    this.tagName = tagName;
    this.tagValue = tagValue;
  }

  public String getName() {
    return name;
  }

  public boolean isCommon() {
    return common;
  }

  public Scope getScope() {
    return scope;
  }

  public String getTag() {
    return tagName == null ? null : String.format("%s:%s", tagName, tagValue);
  }

  public String getSpanTag() {
    return tagName == null ? name : String.format("%s.%s", name, processTagValue(tagValue));
  }

  @SuppressForbidden
  private static String processTagValue(final String tagValue) {
    return tagValue.toLowerCase().replaceAll("\\.", "_");
  }

  public boolean isEnabled(@Nonnull final Verbosity verbosity) {
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

  private static class MetricNames {
    public static final String INSTRUMENTED_PROPAGATION = "instrumented.propagation";
    public static final String INSTRUMENTED_SOURCE = "instrumented.source";
    public static final String INSTRUMENTED_SINK = "instrumented.sink";
    public static final String EXECUTED_PROPAGATION = "executed.propagation";
    public static final String EXECUTED_SOURCE = "executed.source";
    public static final String EXECUTED_SINK = "executed.sink";
    public static final String EXECUTED_TAINTED = "executed.tainted";
    public static final String REQUEST_TAINTED = "request.tainted";
    public static final String TAINTED_FLAT_MODE = "tainted.flat.mode";
  }
}
