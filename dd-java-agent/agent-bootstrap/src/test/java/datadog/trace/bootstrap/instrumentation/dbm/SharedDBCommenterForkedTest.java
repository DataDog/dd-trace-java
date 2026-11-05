package datadog.trace.bootstrap.instrumentation.dbm;

import static datadog.trace.api.config.GeneralConfig.ENV;
import static datadog.trace.api.config.GeneralConfig.SERVICE_NAME;
import static datadog.trace.api.config.GeneralConfig.VERSION;
import static datadog.trace.bootstrap.instrumentation.dbm.SharedDBCommenter.buildComment;
import static datadog.trace.bootstrap.instrumentation.dbm.SharedDBCommenter.containsTraceComment;
import static datadog.trace.bootstrap.instrumentation.dbm.SharedDBCommenter.resetStaticPrefixForTesting;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.junit.utils.config.WithConfigExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.tabletest.junit.TableTest;

@ExtendWith(WithConfigExtension.class)
@WithConfig(key = SERVICE_NAME, value = "test-service")
@WithConfig(key = ENV, value = "test-env")
@WithConfig(key = VERSION, value = "1.0.0")
class SharedDBCommenterForkedTest {

  @BeforeEach
  void setup() {
    resetStaticPrefixForTesting();
  }

  @AfterEach
  void cleanup() {
    resetStaticPrefixForTesting();
  }

  @Test
  void buildCommentGeneratesExpectedFormatForMongoDB() {
    String comment = buildComment("my-db-service", "mongodb", "localhost", "testdb", null);

    assertNotNull(comment);
    assertTrue(comment.contains("ddps='test-service'"));
    assertTrue(comment.contains("dddbs='my-db-service'"));
    assertTrue(comment.contains("dde='test-env'"));
    assertTrue(comment.contains("ddpv='1.0.0'"));
    assertFalse(comment.contains("traceparent"));
  }

  @Test
  void buildCommentIncludesTraceparentWhenProvided() {
    String traceParent = "00-1234567890123456789012345678901234-9876543210987654-01";
    String comment = buildComment("my-db-service", "mongodb", "localhost", "testdb", traceParent);

    assertNotNull(comment);
    assertTrue(comment.contains("ddps='test-service'"));
    assertTrue(comment.contains("dddbs='my-db-service'"));
    assertTrue(comment.contains("traceparent='" + traceParent + "'"));
  }

  @TableTest({
    "scenario         | dbService | dbType    | hostname | dbName | traceParent",
    "null db service  |           | 'mongodb' | 'host'   | 'db'   |            ",
    "empty db service | ''        | 'mongodb' | 'host'   | 'db'   |            ",
    "null hostname    | 'service' | 'mongodb' |          | 'db'   |            ",
    "empty hostname   | 'service' | 'mongodb' | ''       | 'db'   |            ",
    "null db name     | 'service' | 'mongodb' | 'host'   |        |            ",
    "empty db name    | 'service' | 'mongodb' | 'host'   | ''     |            "
  })
  void buildCommentHandlesNullValuesGracefully(
      String dbService, String dbType, String hostname, String dbName, String traceParent) {
    String comment = buildComment(dbService, dbType, hostname, dbName, traceParent);

    assertNotNull(comment);
  }

  @Test
  void buildCommentIncludesHostnameWhenProvided() {
    String comment = buildComment("my-service", "mongodb", "prod-host", "mydb", null);

    assertNotNull(comment);
    assertTrue(comment.contains("ddh='prod-host'"));
    assertTrue(comment.contains("dddb='mydb'"));
  }

  @TableTest({
    "scenario                      | commentContent                                          | expected",
    "service fields                | \"ddps='service',dddbs='db'\"                           | true    ",
    "env and version fields        | \"dde='env',ddpv='1.0'\"                                | true    ",
    "traceparent field             | \"traceparent='00-123-456-01'\"                         | true    ",
    "user comment                  | 'user comment'                                          | false   ",
    "empty comment                 | ''                                                      | false   ",
    "ddps without the right format | 'some other comment with ddps but not the right format' | false   ",
    "multiple fields               | \"ddps='test',dddbs='db',dde='env'\"                    | true    ",
    "field surrounded by text      | \"prefix ddps='service' suffix\"                        | true    "
  })
  void containsTraceCommentDetectsDDFieldsCorrectly(String commentContent, boolean expected) {
    boolean hasComment = containsTraceComment(commentContent);

    assertEquals(expected, hasComment);
  }

  @Test
  void buildCommentEscapesSpecialCharacters() {
    String comment =
        buildComment("service with spaces", "mongodb", "host'with'quotes", "db&name", null);

    assertNotNull(comment);
    assertTrue(comment.contains("dddbs='service+with+spaces'"));
    assertTrue(comment.contains("ddh='host%27with%27quotes'"));
    assertTrue(comment.contains("dddb='db%26name'"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"mongodb", "mysql", "postgresql", "oracle"})
  void buildCommentWorksWithDifferentDatabaseTypes(String dbType) {
    String comment = buildComment("my-service", dbType, "localhost", "testdb", null);

    assertNotNull(comment);
    assertTrue(comment.contains("ddps='test-service'"));
    assertTrue(comment.contains("dddbs='my-service'"));
  }

  @Test
  void buildCommentFormatMatchesExpectedPattern() {
    String comment = buildComment("test-db", "mongodb", "host", "db", "00-trace-span-01");

    assertNotNull(comment);
    // Comment should be comma-separated key=value pairs
    String[] parts = comment.split(",");
    assertTrue(parts.length >= 3);
    for (String part : parts) {
      assertTrue(part.contains("="));
      assertTrue(part.contains("'"));
    }
  }
}
