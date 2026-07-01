package datadog.trace.bootstrap.otlp.logs;

import datadog.trace.bootstrap.otlp.common.OtlpAttributeVisitor;

/** A visitor to visit log records produced by an instrumentation scope. */
public interface OtlpScopedLogsVisitor extends OtlpAttributeVisitor {

  /** Visits an attribute of the upcoming log record. */
  void visitAttribute(int type, String key, Object value);

  /** Visits a log record. */
  void visitLogRecord(OtlpLogRecord logRecord);
}
