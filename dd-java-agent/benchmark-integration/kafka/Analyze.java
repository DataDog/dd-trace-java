import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Summarizes a run-consumer.sh or run-producer.sh results.csv.
 *
 * <p>Consumer, per heap and variant: median fetch throughput, the individual runs (so bimodality is
 * visible), the tracer's cost per record relative to NoAgent in the same session, the change vs a
 * baseline variant, and young GCs per million records.
 *
 * <p>Tracer cost per record = 1 / rate(variant) - 1 / rate(noagent). Unlike the relative throughput
 * drop, which depends on how little work the consumer itself does, this absolute cost carries over
 * to consumers that do real work per message.
 *
 * <p>Producer, per variant: median achieved rate and latency percentiles, with every run's p99.
 *
 * <p>Runs the harness rejected (empty measurement columns) are counted per variant, not averaged.
 *
 * <pre>java Analyze.java results.csv [baseline-label]   (baseline defaults to the first agent variant)
 * </pre>
 */
public class Analyze {
  record Key(String heap, String variant) {}

  public static void main(String[] args) throws IOException {
    if (args.length < 1) {
      System.err.println("usage: java Analyze.java results.csv [baseline-label]");
      System.exit(1);
    }
    List<String> lines = Files.readAllLines(Path.of(args[0]));
    List<String> header = Arrays.asList(lines.get(0).split(",", -1));
    List<Map<String, String>> rows = new ArrayList<>();
    for (String line : lines.subList(1, lines.size())) {
      if (line.isBlank()) {
        continue;
      }
      String[] cells = line.split(",", -1);
      Map<String, String> row = new HashMap<>();
      for (int i = 0; i < header.size(); i++) {
        row.put(header.get(i), i < cells.length ? cells[i] : "");
      }
      rows.add(row);
    }
    String baseline = args.length > 1 ? args[1] : null;
    if (header.contains("fetch_msgs_per_sec")) {
      consumer(rows, baseline);
    } else {
      producer(rows);
    }
  }

  static void consumer(List<Map<String, String>> rows, String baselineArg) {
    Map<Key, List<Double>> rates = new HashMap<>();
    Map<Key, List<Double>> gcs = new HashMap<>();
    Map<Key, Integer> failures = new HashMap<>();
    Set<String> heaps = new LinkedHashSet<>();
    Set<String> order = new LinkedHashSet<>();
    for (Map<String, String> r : rows) {
      Key key = new Key(r.get("heap"), r.get("variant"));
      heaps.add(key.heap());
      order.add(key.variant());
      if (r.get("fetch_msgs_per_sec").isEmpty()) {
        failures.merge(key, 1, Integer::sum);
        continue;
      }
      rates.computeIfAbsent(key, k -> new ArrayList<>()).add(parse(r.get("fetch_msgs_per_sec")));
      double msgs = Math.max(1, parse(r.get("msgs")));
      gcs.computeIfAbsent(key, k -> new ArrayList<>()).add(1e6 * parse(r.get("young_gcs")) / msgs);
    }
    String baseline =
        baselineArg != null
            ? baselineArg
            : order.stream().filter(v -> !v.equals("noagent")).findFirst().orElse(null);

    List<String> sortedHeaps = new ArrayList<>(heaps);
    sortedHeaps.sort(Comparator.comparingLong(Analyze::heapMegabytes).reversed());
    for (String heap : sortedHeaps) {
      System.out.printf("%n== heap %s%n", heap);
      System.out.printf(
          "  %-16s %14s %14s %14s %8s  runs%n",
          "variant",
          "median msgs/s",
          "tracer us/rec",
          baseline != null ? "vs " + baseline : "-",
          "GCs/1M");
      List<Double> noagent = rates.get(new Key(heap, "noagent"));
      List<Double> base = rates.get(new Key(heap, baseline));
      for (String v : order) {
        Key key = new Key(heap, v);
        List<Double> xs = rates.get(key);
        if (xs == null) {
          continue;
        }
        double med = median(xs);
        boolean agent = !v.equals("noagent");
        String cost =
            agent && noagent != null
                ? String.format("%.2f", 1e6 / med - 1e6 / median(noagent))
                : "-";
        String delta =
            agent && base != null ? String.format("%+.1f%%", 100 * (med / median(base) - 1)) : "-";
        System.out.printf(
            "  %-16s %14.0f %14s %14s %8.0f  %s%n",
            v, med, cost, delta, median(gcs.get(key)), runs(xs, 1000, "k"));
      }
      printFailures(heap, order, failures);
    }
  }

  static void producer(List<Map<String, String>> rows) {
    String[] cols = {"records_per_sec", "avg_ms", "p50_ms", "p95_ms", "p99_ms", "p999_ms"};
    Map<String, Map<String, List<Double>>> stats = new LinkedHashMap<>();
    Map<Key, Integer> failures = new HashMap<>();
    Set<String> order = new LinkedHashSet<>();
    for (Map<String, String> r : rows) {
      String v = r.get("variant");
      order.add(v);
      if (r.get("records_per_sec").isEmpty()) {
        failures.merge(new Key("", v), 1, Integer::sum);
        continue;
      }
      Map<String, List<Double>> byCol = stats.computeIfAbsent(v, k -> new HashMap<>());
      for (String c : cols) {
        byCol.computeIfAbsent(c, k -> new ArrayList<>()).add(parse(r.get(c)));
      }
    }
    System.out.printf(
        "%n  %-16s %12s %8s %6s %6s %6s %7s  p99 runs (ms)%n",
        "variant", "records/s", "avg ms", "p50", "p95", "p99", "p99.9");
    for (String v : order) {
      Map<String, List<Double>> byCol = stats.get(v);
      if (byCol == null) {
        continue;
      }
      System.out.printf(
          "  %-16s %12.0f %8.2f %6.0f %6.0f %6.0f %7.0f  %s%n",
          v,
          median(byCol.get("records_per_sec")),
          median(byCol.get("avg_ms")),
          median(byCol.get("p50_ms")),
          median(byCol.get("p95_ms")),
          median(byCol.get("p99_ms")),
          median(byCol.get("p999_ms")),
          runs(byCol.get("p99_ms"), 1, ""));
    }
    printFailures("", order, failures);
  }

  static void printFailures(String heap, Set<String> order, Map<Key, Integer> failures) {
    for (String v : order) {
      Integer n = failures.get(new Key(heap, v));
      if (n != null) {
        System.out.printf("  %s: %d rejected run(s), excluded above%n", v, n);
      }
    }
  }

  static String runs(List<Double> xs, double scale, String suffix) {
    List<Double> sorted = new ArrayList<>(xs);
    Collections.sort(sorted);
    return sorted.stream()
        .map(x -> String.format("%.0f%s", x / scale, suffix))
        .collect(Collectors.joining("/"));
  }

  static double median(List<Double> xs) {
    List<Double> sorted = new ArrayList<>(xs);
    Collections.sort(sorted);
    int n = sorted.size();
    return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
  }

  static double parse(String s) {
    return s.isEmpty() ? 0 : Double.parseDouble(s);
  }

  static long heapMegabytes(String heap) {
    String h = heap.toLowerCase();
    long n = Long.parseLong(h.replaceAll("[^0-9]", ""));
    return h.endsWith("g") ? n * 1024 : n;
  }
}
