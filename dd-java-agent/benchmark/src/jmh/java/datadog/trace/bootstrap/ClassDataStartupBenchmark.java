package datadog.trace.bootstrap;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/** Fresh-JVM benchmark for comparing class-data archive layouts through application main. */
public final class ClassDataStartupBenchmark {
  private static final List<String> DEFAULT_LAYOUTS =
      Arrays.asList(
          "baseline",
          "production",
          "stored-10",
          "stored-25",
          "stored-50",
          "stored-75",
          "stored-100",
          "packed-64",
          "packed-64-dedup",
          "packed-256",
          "packed-1024",
          "packed-all");

  private ClassDataStartupBenchmark() {}

  public static void main(String[] args) throws Exception {
    if (args.length < 3 || args.length > 4) {
      throw new IllegalArgumentException(
          "Expected: <benchmark-dir> <java-executable> <benchmark-jar> [scenario]");
    }
    File benchmarkDir = new File(args[0]);
    String scenario = args.length == 4 ? args[3] : "default";
    List<String> layouts = configuredLayouts();
    int warmups = Integer.getInteger("datadog.classdata.benchmark.warmups", 5);
    int repetitions = Integer.getInteger("datadog.classdata.benchmark.repetitions", 30);

    for (String layout : layouts) {
      for (int i = 0; i < warmups; i++) {
        launch(benchmarkDir, layout, args[1], args[2], scenario);
      }
    }

    Map<String, List<Double>> timings = new LinkedHashMap<>();
    for (String layout : layouts) {
      timings.put(layout, new ArrayList<Double>());
    }
    List<String> order = new ArrayList<>();
    for (int i = 0; i < repetitions; i++) {
      order.addAll(layouts);
    }
    Collections.shuffle(order, new Random(0xDDC1A55L));
    for (String layout : order) {
      timings.get(layout).add(launch(benchmarkDir, layout, args[1], args[2], scenario));
    }

    System.out.println("layout,runs,mean_ms,median_ms,p95_ms,stddev_ms,jar_bytes");
    for (String layout : layouts) {
      List<Double> values = timings.get(layout);
      System.out.printf(
          "%s,%d,%.3f,%.3f,%.3f,%.3f,%d%n",
          layout,
          values.size(),
          mean(values),
          percentile(values, 0.50),
          percentile(values, 0.95),
          standardDeviation(values),
          new File(benchmarkDir, layout + ".jar").length());
    }
  }

  private static List<String> configuredLayouts() {
    String configured = System.getProperty("datadog.classdata.benchmark.layouts");
    return configured == null || configured.isEmpty()
        ? DEFAULT_LAYOUTS
        : Arrays.asList(configured.split(","));
  }

  private static double launch(
      File benchmarkDir, String layout, String javaExecutable, String benchmarkJar, String scenario)
      throws Exception {
    long started = System.nanoTime();
    List<String> command = new ArrayList<>();
    command.add(javaExecutable);
    command.addAll(ClassDataBenchmarkArtifacts.agentOptions(scenario));
    command.add("-javaagent:" + new File(benchmarkDir, layout + ".jar").getAbsolutePath());
    command.add("-cp");
    command.add(benchmarkJar);
    command.add(ClassDataBenchmarkTarget.class.getName());
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    boolean ready = false;
    long readyAt = 0;
    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (ClassDataBenchmarkTarget.READY.equals(line)) {
          ready = true;
          readyAt = System.nanoTime();
        }
      }
    }
    if (!process.waitFor(30, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      throw new IllegalStateException("Timed out starting " + layout + " agent");
    }
    if (!ready || process.exitValue() != 0) {
      throw new IllegalStateException(
          layout + " startup failed with exit code " + process.exitValue());
    }
    return (readyAt - started) / 1_000_000.0;
  }

  private static double mean(List<Double> values) {
    double total = 0;
    for (double value : values) {
      total += value;
    }
    return total / values.size();
  }

  private static double percentile(List<Double> values, double percentile) {
    List<Double> sorted = new ArrayList<>(values);
    sorted.sort(Comparator.naturalOrder());
    int index = (int) Math.ceil(percentile * sorted.size()) - 1;
    return sorted.get(Math.max(0, index));
  }

  private static double standardDeviation(List<Double> values) {
    double mean = mean(values);
    double sum = 0;
    for (double value : values) {
      double delta = value - mean;
      sum += delta * delta;
    }
    return Math.sqrt(sum / values.size());
  }
}
