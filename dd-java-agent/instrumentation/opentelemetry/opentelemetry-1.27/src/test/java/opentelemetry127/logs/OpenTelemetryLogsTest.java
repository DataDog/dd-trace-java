package opentelemetry127.logs;

import static io.opentelemetry.api.common.AttributeKey.booleanKey;
import static io.opentelemetry.api.common.AttributeKey.doubleKey;
import static io.opentelemetry.api.common.AttributeKey.longKey;
import static io.opentelemetry.api.common.AttributeKey.stringKey;
import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.bootstrap.otel.common.OtelInstrumentationScope;
import datadog.trace.bootstrap.otel.logs.data.OtelLogRecordProcessor;
import datadog.trace.bootstrap.otlp.logs.OtlpLogRecord;
import datadog.trace.bootstrap.otlp.logs.OtlpLogsVisitor;
import datadog.trace.bootstrap.otlp.logs.OtlpScopedLogsVisitor;
import datadog.trace.junit.utils.config.WithConfig;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.logs.Logger;
import io.opentelemetry.api.logs.LoggerProvider;
import io.opentelemetry.api.logs.Severity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@WithConfig(key = "logs.otel.enabled", value = "true")
class OpenTelemetryLogsTest extends AbstractInstrumentationTest {

  private final LogsReader logsReader = new LogsReader();

  @BeforeEach
  void drainQueue() {
    // drain any stale log records from the shared processor queue before each test
    OtelLogRecordProcessor.INSTANCE.collectLogs(LogsDrainer.INSTANCE);
  }

  @ParameterizedTest
  @EnumSource(
      value = Severity.class,
      names = {"TRACE", "DEBUG", "INFO", "WARN", "ERROR", "FATAL"})
  void testSeverity(Severity severity) {
    LoggerProvider loggerProvider = GlobalOpenTelemetry.get().getLogsBridge();
    Logger logger = loggerProvider.get("test-severity");

    logger.logRecordBuilder().setBody("test message").setSeverity(severity).emit();

    OtelLogRecordProcessor.INSTANCE.collectLogs(logsReader);

    assertEquals(1, logsReader.logs.size());
    CapturedLog log = logsReader.logs.get(0);
    assertEquals("test-severity", log.scopeName);
    assertEquals(severity.getSeverityNumber(), log.severityNumber);
    assertEquals("test message", log.bodyValue);
  }

  @Test
  void testSeverityText() {
    Logger logger = GlobalOpenTelemetry.get().getLogsBridge().get("test-severity-text");

    logger
        .logRecordBuilder()
        .setBody("message")
        .setSeverity(Severity.INFO)
        .setSeverityText("custom-level")
        .emit();

    OtelLogRecordProcessor.INSTANCE.collectLogs(logsReader);

    assertEquals(1, logsReader.logs.size());
    CapturedLog log = logsReader.logs.get(0);
    assertEquals(Severity.INFO.getSeverityNumber(), log.severityNumber);
    assertEquals("custom-level", log.severityText);
  }

  @Test
  void testAttributes() {
    Logger logger = GlobalOpenTelemetry.get().getLogsBridge().get("test-attributes");

    logger
        .logRecordBuilder()
        .setBody("attributed message")
        .setAttribute(stringKey("str.key"), "str-value")
        .setAttribute(longKey("long.key"), 42L)
        .setAttribute(booleanKey("bool.key"), true)
        .setAttribute(doubleKey("double.key"), 1.5)
        .emit();

    OtelLogRecordProcessor.INSTANCE.collectLogs(logsReader);

    assertEquals(1, logsReader.logs.size());
    CapturedLog log = logsReader.logs.get(0);
    assertEquals("test-attributes", log.scopeName);
    assertEquals("attributed message", log.bodyValue);
    assertEquals("str-value", log.attributes.get("str.key"));
    assertEquals(42L, log.attributes.get("long.key"));
    assertEquals(true, log.attributes.get("bool.key"));
    assertEquals(1.5, log.attributes.get("double.key"));
  }

  @Test
  void testMultipleScopes() {
    Logger loggerA = GlobalOpenTelemetry.get().getLogsBridge().get("scope-a");
    Logger loggerB = GlobalOpenTelemetry.get().getLogsBridge().get("scope-b");

    loggerA.logRecordBuilder().setBody("a-1").setSeverity(Severity.INFO).emit();
    loggerB.logRecordBuilder().setBody("b-1").setSeverity(Severity.WARN).emit();
    loggerA.logRecordBuilder().setBody("a-2").setSeverity(Severity.DEBUG).emit();

    OtelLogRecordProcessor.INSTANCE.collectLogs(logsReader);

    // logs are sorted by scope name, so all scope-a logs come before scope-b logs
    assertEquals(3, logsReader.logs.size());

    List<CapturedLog> scopeALogs =
        logsReader.logs.stream()
            .filter(l -> "scope-a".equals(l.scopeName))
            .collect(Collectors.toList());
    List<CapturedLog> scopeBLogs =
        logsReader.logs.stream()
            .filter(l -> "scope-b".equals(l.scopeName))
            .collect(Collectors.toList());

    assertEquals(2, scopeALogs.size());
    assertEquals("a-1", scopeALogs.get(0).bodyValue);
    assertEquals("a-2", scopeALogs.get(1).bodyValue);

    assertEquals(1, scopeBLogs.size());
    assertEquals("b-1", scopeBLogs.get(0).bodyValue);
  }

  static class CapturedLog {
    final String scopeName;
    final int severityNumber;
    final String severityText;
    final Object bodyValue;
    final Map<String, Object> attributes;

    CapturedLog(
        String scopeName,
        int severityNumber,
        String severityText,
        Object bodyValue,
        Map<String, Object> attributes) {
      this.scopeName = scopeName;
      this.severityNumber = severityNumber;
      this.severityText = severityText;
      this.bodyValue = bodyValue;
      this.attributes = attributes;
    }
  }

  static class LogsReader implements OtlpLogsVisitor, OtlpScopedLogsVisitor {
    final List<CapturedLog> logs = new ArrayList<>();
    private String currentScopeName;
    private final Map<String, Object> currentAttributes = new HashMap<>();

    @Override
    public OtlpScopedLogsVisitor visitScopedLogs(OtelInstrumentationScope scope) {
      currentScopeName = scope.getName().toString();
      return this;
    }

    @Override
    public void visitAttribute(int type, String key, Object value) {
      currentAttributes.put(key, value);
    }

    @Override
    public void visitLogRecord(OtlpLogRecord logRecord) {
      logs.add(
          new CapturedLog(
              currentScopeName,
              logRecord.severityNumber,
              logRecord.severityText,
              logRecord.bodyValue,
              new HashMap<>(currentAttributes)));
      currentAttributes.clear();
    }
  }

  private static class LogsDrainer implements OtlpLogsVisitor {
    public static final LogsDrainer INSTANCE = new LogsDrainer();

    @Override
    public OtlpScopedLogsVisitor visitScopedLogs(OtelInstrumentationScope scope) {
      return new OtlpScopedLogsVisitor() {
        @Override
        public void visitAttribute(int type, String key, Object value) {}

        @Override
        public void visitLogRecord(OtlpLogRecord record) {}
      };
    }
  }
}
