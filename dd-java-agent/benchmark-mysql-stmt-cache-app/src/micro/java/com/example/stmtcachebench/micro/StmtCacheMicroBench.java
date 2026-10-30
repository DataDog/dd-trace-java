package com.example.stmtcachebench.micro;

import java.io.IOException;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class StmtCacheMicroBench {

  static final String CACHE_ON = "cachePrepStmts=true&prepStmtCacheSize=250&prepStmtCacheSqlLimit=2048";
  static final String CACHE_OFF = "cachePrepStmts=false";

  static final Map<String, String> QUERIES = new LinkedHashMap<>();

  static {
    QUERIES.put("simple", "SELECT id, name, price FROM micro_item WHERE id = ?");
    QUERIES.put(
        "orm",
        "select m1_0.id,m1_0.sku,m1_0.category_id,m1_0.name,m1_0.description,m1_0.price,"
            + "m1_0.stock,m1_0.active,m1_0.created_at,m2_0.id,m2_0.sku,m2_0.name,m2_0.price"
            + " from micro_item m1_0 left join micro_item m2_0 on m2_0.id=m1_0.related_id"
            + " where m1_0.id=? and m1_0.active=? and m1_0.category_id between ? and ?"
            + " order by m1_0.id limit ?");
  }

  static final int ROWS = 10_000;
  static final int OPS_PER_CLOCK_CHECK = 64;

  static final int OPS_PER_S = 0;
  static final int WALL_US_PER_OP = 1;
  static final int CPU_US_PER_OP = 2;
  static final int ALLOC_B_PER_OP = 3;

  private static final com.sun.management.ThreadMXBean THREADS =
      (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

  public static void main(String[] args) throws Exception {
    Map<String, String> a = parse(args);
    String url = a.getOrDefault("url", "jdbc:mysql://127.0.0.1:13306/bench");
    String user = a.getOrDefault("user", "bench");
    String password = a.getOrDefault("password", "bench");
    int warmupRounds = Integer.parseInt(a.getOrDefault("warmup-rounds", "1"));
    int rounds = Integer.parseInt(a.getOrDefault("rounds", "5"));
    double seconds = Double.parseDouble(a.getOrDefault("seconds", "3"));
    String[] workloads = a.getOrDefault("workloads", "prepare-only,prepare-exec").split(",");
    String[] queries = a.getOrDefault("queries", "simple,orm").split(",");
    Path out = Paths.get(a.getOrDefault("out", "results/micro-" + System.currentTimeMillis()));
    Files.createDirectories(out);

    String sep = url.contains("?") ? "&" : "?";
    String base = url + sep + "useSSL=false&allowPublicKeyRetrieval=true";
    seed(base, user, password);

    Map<String, Connection> conns = new LinkedHashMap<>();
    conns.put("off", DriverManager.getConnection(base + "&" + CACHE_OFF, user, password));
    conns.put("on", DriverManager.getConnection(base + "&" + CACHE_ON, user, password));

    String driver = conns.get("off").getMetaData().getDriverVersion();
    String server = conns.get("off").getMetaData().getDatabaseProductVersion();
    Map<String, List<double[]>> samples = new LinkedHashMap<>();
    for (int round = -warmupRounds; round < rounds; round++) {
      List<String> order = new ArrayList<>(conns.keySet());
      if ((round & 1) == 1) {
        Collections.reverse(order);
      }
      for (String workload : workloads) {
        for (String q : queries) {
          for (String variant : order) {
            double[] m = measure(conns.get(variant), workload, QUERIES.get(q), seconds);
            String key = workload + "\t" + q + "\t" + variant;
            System.out.printf(
                Locale.ROOT,
                "%s %-12s %-6s %-3s %10.0f ops/s  wall %7.2f us/op  cpu %7.2f us/op  alloc %7.0f B/op%n",
                round < 0 ? "warmup" : "round" + round,
                workload,
                q,
                variant,
                m[0],
                m[1],
                m[2],
                m[3]);
            if (round >= 0) {
              samples.computeIfAbsent(key, k -> new ArrayList<>()).add(m);
            }
          }
        }
      }
    }
    for (Connection c : conns.values()) {
      c.close();
    }
    report(out, samples, workloads, queries, a, rounds, seconds, driver, server);
  }

  static double[] measure(Connection c, String workload, String sql, double seconds)
      throws SQLException {
    boolean exec = workload.equals("prepare-exec");
    if (!exec && !workload.equals("prepare-only")) {
      throw new IllegalArgumentException("unknown workload " + workload);
    }
    boolean orm = sql.indexOf('?') != sql.lastIndexOf('?');
    long tid = Thread.currentThread().getId();
    long deadline = System.nanoTime() + (long) (seconds * 1e9);
    long ops = 0;
    long sink = 0;
    long cpu0 = THREADS.getCurrentThreadCpuTime();
    long alloc0 = THREADS.getThreadAllocatedBytes(tid);
    long t0 = System.nanoTime();
    long now;
    do {
      for (int i = 0; i < OPS_PER_CLOCK_CHECK; i++) {
        int id = 1 + (int) ((ops * 7919) % ROWS);
        try (PreparedStatement ps = c.prepareStatement(sql)) {
          bind(ps, id, orm);
          if (exec) {
            try (ResultSet rs = ps.executeQuery()) {
              while (rs.next()) {
                sink += rs.getInt(1);
              }
            }
          } else {
            sink += id;
          }
        }
        ops++;
      }
      now = System.nanoTime();
    } while (now < deadline);
    long wall = now - t0;
    long cpu = THREADS.getCurrentThreadCpuTime() - cpu0;
    long alloc = THREADS.getThreadAllocatedBytes(tid) - alloc0;
    if (sink == 42) {
      System.out.print("");
    }
    return new double[] {ops / (wall / 1e9), wall / 1e3 / ops, cpu / 1e3 / ops, (double) alloc / ops};
  }

  private static void bind(PreparedStatement ps, int id, boolean orm) throws SQLException {
    ps.setInt(1, id);
    if (orm) {
      ps.setBoolean(2, true);
      ps.setInt(3, 0);
      ps.setInt(4, 1000);
      ps.setInt(5, 1);
    }
  }

  static void seed(String base, String user, String password) throws SQLException {
    try (Connection c = DriverManager.getConnection(base + "&rewriteBatchedStatements=true", user, password);
        Statement s = c.createStatement()) {
      s.execute(
          "CREATE TABLE IF NOT EXISTS micro_item (id INT PRIMARY KEY, sku VARCHAR(32) NOT NULL,"
              + " category_id INT NOT NULL, name VARCHAR(100) NOT NULL, description VARCHAR(255),"
              + " price DECIMAL(10,2) NOT NULL, stock INT NOT NULL, active BOOLEAN NOT NULL,"
              + " created_at DATETIME NOT NULL, related_id INT, KEY idx_cat (category_id))");
      try (ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM micro_item")) {
        rs.next();
        if (rs.getInt(1) == ROWS) {
          return;
        }
      }
      s.execute("TRUNCATE TABLE micro_item");
      try (PreparedStatement ps =
          c.prepareStatement("INSERT INTO micro_item VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW(), ?)")) {
        for (int i = 1; i <= ROWS; i++) {
          ps.setInt(1, i);
          ps.setString(2, "SKU-" + i);
          ps.setInt(3, i % 100);
          ps.setString(4, "Item " + i);
          ps.setString(5, "Description of item " + i);
          ps.setBigDecimal(6, java.math.BigDecimal.valueOf(i % 10_000, 2));
          ps.setInt(7, i % 500);
          ps.setBoolean(8, true);
          ps.setInt(9, 1 + (i * 31) % ROWS);
          ps.addBatch();
          if (i % 1000 == 0) {
            ps.executeBatch();
          }
        }
      }
    }
  }

  static void report(
      Path out,
      Map<String, List<double[]>> samples,
      String[] workloads,
      String[] queries,
      Map<String, String> args,
      int rounds,
      double seconds,
      String driver,
      String server)
      throws IOException {
    try (PrintWriter tsv =
        new PrintWriter(Files.newBufferedWriter(out.resolve("micro.tsv"), StandardCharsets.UTF_8))) {
      tsv.println("workload\tquery\tvariant\tround\tops_per_s\twall_us_per_op\tcpu_us_per_op\talloc_b_per_op");
      for (Map.Entry<String, List<double[]>> e : samples.entrySet()) {
        int r = 0;
        for (double[] m : e.getValue()) {
          tsv.printf(Locale.ROOT, "%s\t%d\t%.1f\t%.3f\t%.3f\t%.1f%n", e.getKey(), r++, m[0], m[1], m[2], m[3]);
        }
      }
    }
    try (PrintWriter md =
        new PrintWriter(Files.newBufferedWriter(out.resolve("micro.md"), StandardCharsets.UTF_8))) {
      md.println("# Connector/J statement cache microbenchmark (no agent)");
      md.println();
      md.println("- java: " + System.getProperty("java.version") + " " + System.getProperty("java.vm.name"));
      md.println("- os: " + System.getProperty("os.name") + " " + System.getProperty("os.arch"));
      md.println("- driver: " + driver);
      md.println("- server: MySQL " + server);
      md.println("- rounds: " + rounds + " x " + seconds + "s per cell, median shown");
      md.println("- url: " + args.getOrDefault("url", "jdbc:mysql://127.0.0.1:13306/bench"));
      md.println();
      md.println(
          "| workload | query | off ops/s | on ops/s | off CPU us/op | on CPU us/op | CPU saved/op | CPU delta | off wall us/op | on wall us/op | off B/op | on B/op |");
      md.println("|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|");
      for (String w : workloads) {
        for (String q : queries) {
          List<double[]> off = samples.get(w + "\t" + q + "\toff");
          List<double[]> on = samples.get(w + "\t" + q + "\ton");
          if (off == null || on == null) {
            continue;
          }
          double offCpu = median(off, CPU_US_PER_OP);
          double onCpu = median(on, CPU_US_PER_OP);
          md.printf(
              Locale.ROOT,
              "| %s | %s | %.0f | %.0f | %.2f | %.2f | %.2f | %+.1f%% | %.2f | %.2f | %.0f | %.0f |%n",
              w,
              q,
              median(off, OPS_PER_S),
              median(on, OPS_PER_S),
              offCpu,
              onCpu,
              offCpu - onCpu,
              (onCpu / offCpu - 1) * 100,
              median(off, WALL_US_PER_OP),
              median(on, WALL_US_PER_OP),
              median(off, ALLOC_B_PER_OP),
              median(on, ALLOC_B_PER_OP));
        }
      }
    }
    System.out.println(new String(Files.readAllBytes(out.resolve("micro.md")), StandardCharsets.UTF_8));
  }

  static double median(List<double[]> rows, int field) {
    List<Double> v = new ArrayList<>();
    for (double[] r : rows) {
      v.add(r[field]);
    }
    Collections.sort(v);
    int n = v.size();
    return n % 2 == 1 ? v.get(n / 2) : (v.get(n / 2 - 1) + v.get(n / 2)) / 2;
  }

  static Map<String, String> parse(String[] args) {
    Map<String, String> m = new HashMap<>();
    for (int i = 0; i + 1 < args.length; i += 2) {
      if (!args[i].startsWith("--")) {
        throw new IllegalArgumentException("expected --key value, got " + args[i]);
      }
      m.put(args[i].substring(2), args[i + 1]);
    }
    return m;
  }
}
