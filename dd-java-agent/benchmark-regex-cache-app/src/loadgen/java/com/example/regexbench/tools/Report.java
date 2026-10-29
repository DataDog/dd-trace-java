package com.example.regexbench.tools;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class Report {

  static final String[] LOAD_FIELDS = {
    "requests",
    "errors",
    "seconds",
    "rps",
    "p50Us",
    "p90Us",
    "p99Us",
    "maxUs",
    "cpuMs",
    "cpuUsPerReq",
    "gcCount",
    "gcMs",
    "regexHits",
    "regexMisses",
    "regexAdmissions"
  };

  public static void main(String[] args) throws IOException {
    Path tsv = Paths.get(args[0]);
    Path outDir = Paths.get(args[1]);
    String meta = args.length > 2 ? args[2] : "";

    Map<String, List<Double>> startup = new LinkedHashMap<>();
    Map<String, Map<String, String[]>> load = new LinkedHashMap<>();
    List<String> endpoints = new ArrayList<>();
    for (String line : Files.readAllLines(tsv, StandardCharsets.UTF_8)) {
      String[] f = line.split("\t");
      if (f[0].equals("startup")) {
        startup.computeIfAbsent(f[1], k -> new ArrayList<>()).add(Double.parseDouble(f[3]));
      } else if (f[0].equals("load")) {
        String[] values = new String[LOAD_FIELDS.length];
        System.arraycopy(f, 3, values, 0, values.length);
        load.computeIfAbsent(f[1], k -> new LinkedHashMap<>()).put(f[2], values);
        if (!endpoints.contains(f[2])) {
          endpoints.add(f[2]);
        }
      }
    }
    List<String> configs = new ArrayList<>(startup.keySet());
    for (String c : load.keySet()) {
      if (!configs.contains(c)) {
        configs.add(c);
      }
    }
    String baseline = configs.isEmpty() ? null : configs.get(0);

    writeJson(outDir.resolve("summary.json"), meta, configs, startup, load, endpoints);
    writeMarkdown(outDir.resolve("summary.md"), meta, configs, baseline, startup, load, endpoints);
  }

  private static void writeJson(
      Path out,
      String meta,
      List<String> configs,
      Map<String, List<Double>> startup,
      Map<String, Map<String, String[]>> load,
      List<String> endpoints)
      throws IOException {
    StringBuilder sb = new StringBuilder();
    sb.append("{\n  \"meta\": {");
    boolean first = true;
    for (String kv : meta.split(";")) {
      int eq = kv.indexOf('=');
      if (eq <= 0) {
        continue;
      }
      sb.append(first ? "\n" : ",\n");
      first = false;
      sb.append("    ").append(q(kv.substring(0, eq))).append(": ").append(q(kv.substring(eq + 1)));
    }
    sb.append("\n  },\n  \"configs\": {");
    for (int ci = 0; ci < configs.size(); ci++) {
      String c = configs.get(ci);
      sb.append(ci == 0 ? "\n" : ",\n").append("    ").append(q(c)).append(": {\n");
      List<Double> s = startup.getOrDefault(c, Collections.emptyList());
      sb.append("      \"startupMs\": {\"median\": ")
          .append(num(median(s)))
          .append(", \"runs\": ")
          .append(s.toString())
          .append("},\n      \"endpoints\": {");
      Map<String, String[]> eps = load.getOrDefault(c, Collections.emptyMap());
      boolean firstEp = true;
      for (String ep : endpoints) {
        String[] v = eps.get(ep);
        if (v == null) {
          continue;
        }
        sb.append(firstEp ? "\n" : ",\n").append("        ").append(q(ep)).append(": {");
        firstEp = false;
        for (int i = 0; i < LOAD_FIELDS.length; i++) {
          sb.append(i == 0 ? "" : ", ").append(q(LOAD_FIELDS[i])).append(": ").append(v[i]);
        }
        sb.append("}");
      }
      sb.append("\n      }\n    }");
    }
    sb.append("\n  }\n}\n");
    Files.write(out, sb.toString().getBytes(StandardCharsets.UTF_8));
  }

  private static void writeMarkdown(
      Path out,
      String meta,
      List<String> configs,
      String baseline,
      Map<String, List<Double>> startup,
      Map<String, Map<String, String[]>> load,
      List<String> endpoints)
      throws IOException {
    try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out, StandardCharsets.UTF_8))) {
      w.println("# Regex cache benchmark");
      w.println();
      for (String kv : meta.split(";")) {
        if (kv.indexOf('=') > 0) {
          w.println("- " + kv.replaceFirst("=", ": "));
        }
      }
      w.println();
      w.println("## Startup to first successful request (ms)");
      w.println();
      w.println("| config | median | min | max | runs |");
      w.println("|---|---:|---:|---:|---:|");
      for (String c : configs) {
        List<Double> s = startup.get(c);
        if (s == null || s.isEmpty()) {
          continue;
        }
        w.printf(
            Locale.ROOT,
            "| %s | %.0f | %.0f | %.0f | %d |%n",
            c,
            median(s),
            Collections.min(s),
            Collections.max(s),
            s.size());
      }
      w.println();
      w.println("## Load (relative columns vs `" + baseline + "`)");
      w.println();
      w.println(
          "| endpoint | config | req/s | req/s vs base | p50 us | p99 us | CPU us/req |"
              + " CPU/req vs base | GC ms | errors |");
      w.println("|---|---|---:|---:|---:|---:|---:|---:|---:|---:|");
      for (String ep : endpoints) {
        String[] base = load.getOrDefault(baseline, Collections.emptyMap()).get(ep);
        for (String c : configs) {
          String[] v = load.getOrDefault(c, Collections.emptyMap()).get(ep);
          if (v == null) {
            continue;
          }
          w.printf(
              Locale.ROOT,
              "| %s | %s | %.0f | %s | %.1f | %.1f | %.2f | %s | %s | %s |%n",
              ep,
              c,
              d(v, "rps"),
              rel(v, base, "rps"),
              d(v, "p50Us"),
              d(v, "p99Us"),
              d(v, "cpuUsPerReq"),
              rel(v, base, "cpuUsPerReq"),
              v[idx("gcMs")],
              v[idx("errors")]);
        }
      }
      w.println();
      w.println("## RegexCache counters at end of each config's load phase");
      w.println();
      w.println(
          "-1 means the counters were not reachable (no agent, or agent without RegexCache).");
      w.println();
      w.println("| config | hits | misses | admissions |");
      w.println("|---|---:|---:|---:|");
      for (String c : configs) {
        Map<String, String[]> eps = load.get(c);
        if (eps == null || eps.isEmpty()) {
          continue;
        }
        String[] last = null;
        for (String[] v : eps.values()) {
          last = v;
        }
        w.printf(
            "| %s | %s | %s | %s |%n",
            c, last[idx("regexHits")], last[idx("regexMisses")], last[idx("regexAdmissions")]);
      }
    }
  }

  private static int idx(String field) {
    for (int i = 0; i < LOAD_FIELDS.length; i++) {
      if (LOAD_FIELDS[i].equals(field)) {
        return i;
      }
    }
    throw new IllegalArgumentException(field);
  }

  private static double d(String[] v, String field) {
    return Double.parseDouble(v[idx(field)]);
  }

  private static String rel(String[] v, String[] base, String field) {
    if (base == null || v == base) {
      return "-";
    }
    double b = d(base, field);
    return b == 0 ? "-" : String.format(Locale.ROOT, "%+.1f%%", (d(v, field) / b - 1) * 100);
  }

  static double median(List<Double> values) {
    if (values.isEmpty()) {
      return Double.NaN;
    }
    List<Double> s = new ArrayList<>(values);
    Collections.sort(s);
    int n = s.size();
    return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2;
  }

  private static String num(double d) {
    return Double.isNaN(d) ? "null" : String.format(Locale.ROOT, "%.3f", d);
  }

  private static String q(String s) {
    return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }
}
