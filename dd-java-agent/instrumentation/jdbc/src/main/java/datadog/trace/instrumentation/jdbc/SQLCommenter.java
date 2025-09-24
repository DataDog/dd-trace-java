package datadog.trace.instrumentation.jdbc;

import datadog.trace.core.database.SharedDBCommenter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SQLCommenter {
  private static final Logger log = LoggerFactory.getLogger(SQLCommenter.class);
  // SQL-specific constants, rest defined in SharedDBCommenter
  private static final char SPACE = ' ';
  private static final String OPEN_COMMENT = "/*";
  private static final int OPEN_COMMENT_LEN = OPEN_COMMENT.length();
  private static final String CLOSE_COMMENT = "*/";

  // Size estimation for StringBuilder pre-allocation
  private static final int SPACE_CHARS = 2; // Leading and trailing spaces
  private static final int COMMENT_DELIMITERS = 4; // "/*" + "*/"
  private static final int BUFFER_EXTRA = 4;
  private static final int SQL_COMMENT_OVERHEAD = SPACE_CHARS + COMMENT_DELIMITERS + BUFFER_EXTRA;

  protected static String getFirstWord(String sql) {
    int beginIndex = 0;
    while (beginIndex < sql.length() && Character.isWhitespace(sql.charAt(beginIndex))) {
      beginIndex++;
    }
    int endIndex = beginIndex;
    while (endIndex < sql.length() && !Character.isWhitespace(sql.charAt(endIndex))) {
      endIndex++;
    }
    return sql.substring(beginIndex, endIndex);
  }

  public static String inject(
      String sql,
      String dbService,
      String dbType,
      String hostname,
      String dbName,
      String traceParent,
      boolean preferAppend) {
    if (sql == null || sql.isEmpty()) {
      return sql;
    }
    boolean appendComment = preferAppend;
    if (dbType != null) {
      final String firstWord = getFirstWord(sql);

      // The Postgres JDBC parser doesn't allow SQL comments anywhere in a JDBC
      // callable statements
      // https://github.com/pgjdbc/pgjdbc/blob/master/pgjdbc/src/main/java/org/postgresql/core/Parser.java#L1038
      // TODO: Could we inject the comment after the JDBC has been converted to
      // standard SQL?
      if (firstWord.startsWith("{") && dbType.startsWith("postgres")) {
        return sql;
      }

      // Append the comment for mysql JDBC callable statements
      if (firstWord.startsWith("{") && "mysql".equals(dbType)) {
        appendComment = true;
      }

      // Both Postgres and MySQL are unhappy with anything before CALL in a stored
      // procedure invocation, but they seem ok with it after so we force append mode
      if (firstWord.equalsIgnoreCase("call")) {
        appendComment = true;
      }

      // Append the comment in the case of a pg_hint_plan extension
      if (dbType.startsWith("postgres") && sql.contains("/*+")) {
        appendComment = true;
      }
    }
    if (hasDDComment(sql, appendComment)) {
      return sql;
    }

    String commentContent =
        SharedDBCommenter.buildComment(dbService, dbType, hostname, dbName, traceParent);

    if (commentContent == null) {
      return sql;
    }

    // SQL-specific wrapping with /* */
    StringBuilder sb =
        new StringBuilder(sql.length() + commentContent.length() + SQL_COMMENT_OVERHEAD);
    if (appendComment) {
      sb.append(sql);
      sb.append(SPACE);
    }
    sb.append(OPEN_COMMENT);
    sb.append(commentContent);
    sb.append(CLOSE_COMMENT);
    if (!appendComment) {
      sb.append(SPACE);
      sb.append(sql);
    }
    return sb.toString();
  }

  private static boolean hasDDComment(String sql, boolean appendComment) {
    if ((!sql.endsWith(CLOSE_COMMENT) && appendComment)
        || ((!sql.startsWith(OPEN_COMMENT)) && !appendComment)) {
      return false;
    }

    String commentContent = extractCommentContent(sql, appendComment);
    return SharedDBCommenter.containsTraceComment(commentContent);
  }

  private static String extractCommentContent(String sql, boolean appendComment) {
    int startIdx;
    int endIdx;
    if (appendComment) {
      startIdx = sql.lastIndexOf(OPEN_COMMENT);
      endIdx = sql.lastIndexOf(CLOSE_COMMENT);
    } else {
      startIdx = sql.indexOf(OPEN_COMMENT);
      endIdx = sql.indexOf(CLOSE_COMMENT);
    }
    if (startIdx != -1 && endIdx != -1 && endIdx > startIdx) {
      return sql.substring(startIdx + OPEN_COMMENT_LEN, endIdx);
    }
    return "";
  }
}
