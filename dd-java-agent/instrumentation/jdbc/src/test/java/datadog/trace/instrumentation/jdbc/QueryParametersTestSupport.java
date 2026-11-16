package datadog.trace.instrumentation.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.core.DDSpan;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/** Runs statements against in-memory H2 and HSQLDB and reads the prepared statement spans. */
abstract class QueryParametersTestSupport extends AbstractInstrumentationTest {
  static final String PARAMETER_TAG_PREFIX = "db.query.parameter.";
  static final String TYPE_TAG_PREFIX = "_dd.dbm.query.parameter_type.";
  static final String TRUNCATED_TAG = "_dd.dbm.query.parameters_truncated";

  Connection h2;

  @BeforeEach
  void openDatabase() throws SQLException {
    h2 = DriverManager.getConnection("jdbc:h2:mem:qparams;DB_CLOSE_DELAY=-1");
    try (Statement statement = h2.createStatement()) {
      statement.execute("DROP TABLE IF EXISTS orders");
      statement.execute(
          "CREATE TABLE orders (id BIGINT, status VARCHAR(4000), payload BLOB, notes CLOB)");
    }
    writer.clear();
  }

  @AfterEach
  void closeDatabase() throws SQLException {
    h2.close();
  }

  /** Waits for the given number of prepared statement spans and returns them in order. */
  List<DDSpan> preparedStatementSpans(int expected) {
    blockUntilTracesMatch(traces -> preparedStatementSpans(traces).size() >= expected);
    List<DDSpan> spans = preparedStatementSpans(writer);
    assertEquals(expected, spans.size());
    return spans;
  }

  private static List<DDSpan> preparedStatementSpans(List<List<DDSpan>> traces) {
    return traces.stream()
        .flatMap(List::stream)
        .filter(
            span -> "java-jdbc-prepared_statement".equals(String.valueOf(span.getTag("component"))))
        .sorted((a, b) -> Long.compare(a.getStartTime(), b.getStartTime()))
        .collect(Collectors.toList());
  }

  /** The tags added by the parameter capture, sorted by name. */
  static Map<String, Object> parameterTags(DDSpan span) {
    Map<String, Object> tags = new TreeMap<>();
    for (Map.Entry<String, Object> tag : span.getTags().entrySet()) {
      if (tag.getKey().startsWith(PARAMETER_TAG_PREFIX)
          || tag.getKey().startsWith("_dd.dbm.query.")) {
        tags.put(tag.getKey(), tag.getValue());
      }
    }
    return tags;
  }
}
