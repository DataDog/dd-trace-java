package datadog.trace.instrumentation.jdbc.mysql;

import java.math.BigDecimal;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

public final class StatementCorpus {

  public interface Connector {
    Connection open(String extraUrlParams) throws SQLException;
  }

  public interface ConnectionInspector {
    void inspect(Connection connection) throws SQLException;
  }

  static final int LONG_IN_LIST = 700;
  static final int DISTINCT_STATEMENTS =
      Integer.parseInt(MySqlStatementCacheDefaults.DEFAULT_CACHE_SIZE) + 50;
  static final int CONNECTIONS = 6;

  private final List<String> out = new ArrayList<>();

  private StatementCorpus() {}

  public static List<String> run(final Connector connector) throws SQLException {
    return run(connector, null);
  }

  public static List<String> run(final Connector connector, final ConnectionInspector inspector)
      throws SQLException {
    final StatementCorpus corpus = new StatementCorpus();
    try (Connection c = connector.open("")) {
      corpus.reset(c);
      corpus.literalsAndComments(c);
      corpus.typesAndErrors(c);
      corpus.longStatements(c);
      corpus.manyDistinctStatements(c);
      corpus.calls(c);
      corpus.schemaChange(c);
      if (inspector != null) {
        inspector.inspect(c);
      }
    }
    for (final boolean rewrite : new boolean[] {false, true}) {
      try (Connection c = connector.open("rewriteBatchedStatements=" + rewrite)) {
        corpus.batches(c, rewrite ? "rw" : "plain");
      }
    }
    corpus.sameSqlOnManyConnections(connector);
    try (Connection c = connector.open("")) {
      corpus.dumpTables(c);
    }
    return corpus.out;
  }

  private void reset(final Connection c) throws SQLException {
    try (Statement s = c.createStatement()) {
      s.execute("DROP TABLE IF EXISTS corpus_t");
      s.execute("DROP TABLE IF EXISTS corpus_u");
      s.execute("DROP PROCEDURE IF EXISTS corpus_sel");
      s.execute("DROP PROCEDURE IF EXISTS corpus_out");
      s.execute(
          "CREATE TABLE corpus_t (id INT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(64),"
              + " note VARCHAR(255), n INT, `we?ird` INT DEFAULT 0, KEY idx_n (n))");
      s.execute("CREATE TABLE corpus_u (k VARCHAR(32) PRIMARY KEY, v INT)");
      s.execute(
          "CREATE PROCEDURE corpus_sel(IN x INT) BEGIN"
              + " SELECT id, name FROM corpus_t WHERE n >= x ORDER BY id;"
              + " SELECT COUNT(*) AS c FROM corpus_t; END");
      s.execute("CREATE PROCEDURE corpus_out(IN x INT, OUT y INT) BEGIN SET y = x * 2; END");
    }
  }

  private void literalsAndComments(final Connection c) throws SQLException {
    final String insert = "INSERT INTO corpus_t(name, note, n) VALUES (?, 'what? really?', ?)";
    for (int i = 1; i <= 3; i++) {
      try (PreparedStatement ps = c.prepareStatement(insert, Statement.RETURN_GENERATED_KEYS)) {
        ps.setString(1, "row" + i);
        ps.setInt(2, i * 10);
        exec("insert literal ?" + i, ps, true);
      }
    }
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO corpus_t(name, note, n) VALUES (?, ?, ?), (?, ?, ?), (?, 'lit ?', ?)",
            Statement.RETURN_GENERATED_KEYS)) {
      ps.setString(1, "m1");
      ps.setString(2, "n?1");
      ps.setInt(3, 40);
      ps.setString(4, "m2");
      ps.setNull(5, Types.VARCHAR);
      ps.setInt(6, 50);
      ps.setString(7, "m3");
      ps.setInt(8, 60);
      exec("multi-row insert", ps, true);
    }
    final String[] queries = {
      "SELECT /* is this ? a param */ id, name FROM corpus_t WHERE n = ? -- trailing ? comment\n"
          + "ORDER BY id",
      "SELECT id, note FROM corpus_t WHERE note = 'what? really?' AND n > ? # hash ? comment\n"
          + "ORDER BY id",
      "SELECT 'it''s ?', \"double ? quoted\", 'back\\'slash ?', ?",
      "SELECT `we?ird`, ? FROM corpus_t WHERE id = ?",
      "SELECT name FROM corpus_t WHERE name LIKE ? AND note LIKE '%?%' ORDER BY id",
      "SELECT /*!50000 ? + */ ? AS versioned_comment",
      "SELECT id FROM corpus_t ORDER BY id LIMIT ?"
    };
    for (final String sql : queries) {
      for (int round = 0; round < 2; round++) {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
          final int params = ps.getParameterMetaData().getParameterCount();
          for (int p = 1; p <= params; p++) {
            if (sql.contains("LIKE")) {
              ps.setString(p, round == 0 ? "row%" : "m%");
            } else {
              ps.setInt(p, (round + 1) * 10 + p - 1);
            }
          }
          out.add("params " + params);
          exec(sql, ps);
        }
      }
    }
  }

  private void typesAndErrors(final Connection c) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement("SELECT ? + ?, ? IS NULL, CONCAT(?, '?', ?), HEX(?), ?")) {
      ps.setInt(1, 1);
      ps.setBigDecimal(2, new BigDecimal("2.50"));
      ps.setNull(3, Types.INTEGER);
      ps.setString(4, "a");
      ps.setString(5, "b'c\"d\\e");
      ps.setBytes(6, new byte[] {0, 1, (byte) 0xfe, '?'});
      ps.setTimestamp(7, Timestamp.valueOf("2024-02-29 12:34:56.789"));
      exec("types", ps);
    }
    try (PreparedStatement ps = c.prepareStatement("SELECT ? + ?")) {
      ps.setInt(1, 1);
      exec("missing parameter", ps);
    }
    try (PreparedStatement ps = c.prepareStatement("SELEC ? FROM corpus_t")) {
      ps.setInt(1, 1);
      exec("syntax error", ps);
    }
    try (PreparedStatement ps = c.prepareStatement("SELECT ?")) {
      ps.setInt(2, 1);
      out.add("index out of range: no error");
    } catch (SQLException e) {
      error("index out of range", e);
    }
  }

  private void longStatements(final Connection c) throws SQLException {
    final StringBuilder in = new StringBuilder("SELECT id, n FROM corpus_t WHERE n IN (");
    for (int i = 0; i < LONG_IN_LIST; i++) {
      in.append(i == 0 ? "?" : ", ?");
    }
    in.append(") ORDER BY id");
    final StringBuilder padded =
        new StringBuilder("SELECT ? AS a, '").append(repeat("pad?", 600)).append("' AS b, ? AS c");
    for (final String sql : new String[] {in.toString(), padded.toString()}) {
      out.add("long statement length " + sql.length());
      for (int round = 0; round < 2; round++) {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
          final int params = ps.getParameterMetaData().getParameterCount();
          for (int p = 1; p <= params; p++) {
            ps.setInt(p, (p * 10 + round * 5));
          }
          exec("long round " + round, ps);
        }
      }
    }
  }

  private void manyDistinctStatements(final Connection c) throws SQLException {
    for (int pass = 0; pass < 2; pass++) {
      for (int i = 0; i < DISTINCT_STATEMENTS; i++) {
        if (pass == 1 && i >= 20) {
          break;
        }
        try (PreparedStatement ps =
            c.prepareStatement("SELECT ? + " + i + " AS v, '?" + i + "' AS s")) {
          ps.setInt(1, pass * 1000);
          exec("distinct " + pass + "/" + i, ps);
        }
      }
    }
  }

  private void calls(final Connection c) throws SQLException {
    for (int round = 0; round < 2; round++) {
      try (PreparedStatement ps = c.prepareStatement("CALL corpus_sel(?)")) {
        ps.setInt(1, 20 + round * 20);
        exec("call multi result", ps);
      }
      try (CallableStatement cs = c.prepareCall("{CALL corpus_out(?, ?)}")) {
        cs.setInt(1, 21 + round);
        cs.registerOutParameter(2, Types.INTEGER);
        cs.execute();
        out.add("call out param " + cs.getInt(2));
      }
      try (PreparedStatement ps = c.prepareStatement("CALL corpus_out(?, @corpus_y)")) {
        ps.setInt(1, 5 + round);
        exec("call with session var", ps);
      }
      try (PreparedStatement ps = c.prepareStatement("SELECT @corpus_y")) {
        exec("session var", ps);
      }
    }
  }

  private void schemaChange(final Connection c) throws SQLException {
    final String sql = "SELECT * FROM corpus_t WHERE id = ?";
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setInt(1, 1);
      exec("before alter", ps);
    }
    try (Statement s = c.createStatement()) {
      s.execute("ALTER TABLE corpus_t ADD COLUMN extra INT DEFAULT 7");
    }
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setInt(1, 1);
      exec("after alter", ps);
    }
  }

  private void batches(final Connection c, final String tag) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement("INSERT INTO corpus_u(k, v) VALUES (?, ?)")) {
      for (int i = 0; i < 5; i++) {
        ps.setString(1, tag + "-k?" + i);
        ps.setInt(2, i);
        ps.addBatch();
      }
      batch(tag + " insert batch", ps);
    }
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO corpus_u(k, v) VALUES (?, ?) ON DUPLICATE KEY UPDATE v = v + ?")) {
      for (int i = 3; i < 7; i++) {
        ps.setString(1, tag + "-k?" + i);
        ps.setInt(2, 100 + i);
        ps.setInt(3, 1000);
        ps.addBatch();
      }
      batch(tag + " upsert batch", ps);
    }
    try (PreparedStatement ps =
        c.prepareStatement("UPDATE corpus_t SET n = n + ? WHERE id = ? /* ? */")) {
      for (int i = 1; i <= 3; i++) {
        ps.setInt(1, 1);
        ps.setInt(2, i);
        ps.addBatch();
      }
      batch(tag + " update batch", ps);
    }
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO corpus_t(name, note, n) VALUES (?, 'batch?', ?)",
            Statement.RETURN_GENERATED_KEYS)) {
      for (int i = 0; i < 4; i++) {
        ps.setString(1, tag + i);
        ps.setInt(2, 70 + i);
        ps.addBatch();
      }
      batch(tag + " insert batch with keys", ps);
      dump(tag + " batch generated keys", ps.getGeneratedKeys());
    }
    try (PreparedStatement ps = c.prepareStatement("INSERT INTO corpus_u(k, v) VALUES (?, ?)")) {
      ps.setString(1, tag + "-k?0"); // duplicate key
      ps.setInt(2, 0);
      ps.addBatch();
      ps.setString(1, tag + "-new");
      ps.setInt(2, 1);
      ps.addBatch();
      batch(tag + " failing batch", ps);
    }
  }

  private void sameSqlOnManyConnections(final Connector connector) throws SQLException {
    final Connection[] conns = new Connection[CONNECTIONS];
    try {
      for (int i = 0; i < conns.length; i++) {
        conns[i] = connector.open("");
      }
      final String sql =
          "SELECT id, name, n FROM corpus_t WHERE n >= ? AND note <> '?' ORDER BY id";
      for (int i = 0; i < 60; i++) {
        try (PreparedStatement ps = conns[i % conns.length].prepareStatement(sql)) {
          ps.setInt(1, (i % 8) * 10);
          exec("conn " + (i % conns.length) + " iter " + i, ps);
        }
      }
    } finally {
      for (final Connection conn : conns) {
        if (conn != null) {
          conn.close();
        }
      }
    }
  }

  private void dumpTables(final Connection c) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement("SELECT * FROM corpus_t ORDER BY id")) {
      exec("final corpus_t", ps);
    }
    try (PreparedStatement ps = c.prepareStatement("SELECT * FROM corpus_u ORDER BY k")) {
      exec("final corpus_u", ps);
    }
  }

  private void exec(final String label, final PreparedStatement ps) {
    exec(label, ps, false);
  }

  private void exec(final String label, final PreparedStatement ps, final boolean keys) {
    out.add("> " + label);
    try {
      boolean isResultSet = ps.execute();
      while (true) {
        if (isResultSet) {
          dump("rs", ps.getResultSet());
        } else {
          final int count = ps.getUpdateCount();
          if (count == -1) {
            break;
          }
          out.add("update count " + count);
        }
        isResultSet = ps.getMoreResults();
      }
      if (keys) {
        dump("generated keys", ps.getGeneratedKeys());
      }
    } catch (SQLException e) {
      error(label, e);
    }
  }

  private void batch(final String label, final PreparedStatement ps) {
    out.add("> " + label);
    try {
      out.add("batch counts " + join(ps.executeBatch()));
    } catch (java.sql.BatchUpdateException e) {
      out.add("batch counts on error " + join(e.getUpdateCounts()));
      error(label, e);
    } catch (SQLException e) {
      error(label, e);
    }
  }

  private void dump(final String label, final ResultSet rs) throws SQLException {
    if (rs == null) {
      out.add(label + ": none");
      return;
    }
    try (ResultSet r = rs) {
      final ResultSetMetaData md = r.getMetaData();
      final StringBuilder header = new StringBuilder(label).append(":");
      for (int i = 1; i <= md.getColumnCount(); i++) {
        header.append(' ').append(md.getColumnLabel(i)).append('/').append(md.getColumnTypeName(i));
      }
      out.add(header.toString());
      while (r.next()) {
        final StringBuilder row = new StringBuilder("  row:");
        for (int i = 1; i <= md.getColumnCount(); i++) {
          row.append(" [").append(r.getString(i)).append(']');
        }
        out.add(row.toString());
      }
    }
  }

  private void error(final String label, final SQLException e) {
    out.add(
        "error "
            + label
            + ": "
            + e.getClass().getName()
            + " state="
            + e.getSQLState()
            + " code="
            + e.getErrorCode()
            + " msg="
            + e.getMessage());
  }

  private static String join(final int[] counts) {
    final StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < counts.length; i++) {
      sb.append(i == 0 ? "" : ", ").append(counts[i]);
    }
    return sb.append(']').toString();
  }

  private static String repeat(final String s, final int n) {
    final StringBuilder sb = new StringBuilder(s.length() * n);
    for (int i = 0; i < n; i++) {
      sb.append(s);
    }
    return sb.toString();
  }
}
