package datadog.trace.bootstrap.instrumentation.dbm;

import static datadog.trace.bootstrap.instrumentation.dbm.SharedDBCommenter.containsTraceComment;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * DB-level behavior of {@link SharedDBCommenter#containsTraceComment}: the nine "<key>=" needle set,
 * the {@code String} delegate, and the range overload checking the comment body in place. (The
 * {@code [from, to)} boundary semantics are unit-tested on {@code Strings.regionContains}.)
 */
class SharedDBCommenterContainsTraceCommentTest {

  @Test
  void delegate_wholeString() {
    assertTrue(containsTraceComment("ddps='svc',dde='test'"));
    assertFalse(containsTraceComment("just a plain comment"));
    assertFalse(containsTraceComment(""));
  }

  @Test
  void range_needleInsideCommentBody() {
    String sql = "SELECT 1 /*ddps='svc',dde='test'*/";
    int from = sql.indexOf("/*") + 2;
    int to = sql.indexOf("*/");
    assertTrue(containsTraceComment(sql, from, to));
  }

  @Test
  void range_nonDdCommentBody() {
    String sql = "SELECT 1 /* just a customer comment */";
    int from = sql.indexOf("/*") + 2;
    int to = sql.indexOf("*/");
    assertFalse(containsTraceComment(sql, from, to));
  }

  @Test
  void range_ddNeedleOutsideCommentRegionNotMatched() {
    // The DD needle sits in the statement body, not the comment region we pass -- a whole-string
    // contains would false-positive; the range check must scope to [from, to).
    String sql = "ddps='x' /* clean */";
    int from = sql.indexOf("/*") + 2;
    int to = sql.indexOf("*/");
    assertFalse(containsTraceComment(sql, from, to));
  }
}
