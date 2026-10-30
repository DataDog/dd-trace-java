package com.example.stmtcachebench.tools;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public final class LoadGen {

  public static void main(String[] args) throws Exception {
    Args a = new Args(args);
    String base = a.get("base", "http://127.0.0.1:18080");
    if ("get".equals(a.command)) {
      System.out.println(Http.getBody(new URL(a.get("url", base))).trim());
      return;
    }
    if ("wait".equals(a.command)) {
      long deadline = System.nanoTime() + (long) (a.getDouble("timeout", 120) * 1e9);
      URL url = new URL(base + "/nodb");
      while (System.nanoTime() < deadline) {
        if (Http.ready(url)) {
          return;
        }
        Thread.sleep(50);
      }
      System.err.println("server at " + base + " not ready in time");
      System.exit(1);
    }
    String config = a.get("config", "unknown");
    String[] endpoints = a.get("endpoints", "nodb").split(",");
    double warmup = a.getDouble("warmup", 10);
    double duration = a.getDouble("duration", 30);
    int connections = (int) a.getDouble("connections", 8);
    String out = a.get("out", null);

    for (String ep : endpoints) {
      URL url = new URL(base + "/" + ep);
      if (!Http.ready(url)) {
        throw new IllegalStateException("endpoint " + url + " does not return 200");
      }
      run(url, connections, warmup);
      Map<String, Long> before = Http.stats(base);
      Result r = run(url, connections, duration);
      Map<String, Long> after = Http.stats(base);

      double cpuMs = (after.get("cpuNanos") - before.get("cpuNanos")) / 1e6;
      long n = r.latenciesNanos.length;
      String line =
          String.join(
              "\t",
              "load",
              config,
              ep,
              Long.toString(n),
              Long.toString(r.errors),
              fmt(r.seconds),
              fmt(n / r.seconds),
              fmt(pct(r.latenciesNanos, 50) / 1e3),
              fmt(pct(r.latenciesNanos, 90) / 1e3),
              fmt(pct(r.latenciesNanos, 99) / 1e3),
              fmt(n == 0 ? 0 : r.latenciesNanos[(int) n - 1] / 1e3),
              fmt(cpuMs),
              fmt(n == 0 ? 0 : cpuMs * 1e3 / n),
              Long.toString(after.get("gcCount") - before.get("gcCount")),
              Long.toString(after.get("gcMs") - before.get("gcMs")),
              Long.toString(after.get("cachePrepStmts")),
              Long.toString(after.get("cachedStatements")));
      System.out.printf(
          Locale.ROOT,
          "  %-10s %-9s %10.0f req/s  p50 %8.1f us  p99 %8.1f us  cpu %7.1f us/req  errors %d%n",
          config,
          ep,
          n / r.seconds,
          pct(r.latenciesNanos, 50) / 1e3,
          pct(r.latenciesNanos, 99) / 1e3,
          n == 0 ? 0 : cpuMs * 1e3 / n,
          r.errors);
      if (out != null) {
        try (PrintWriter w = new PrintWriter(new FileWriter(out, true))) {
          w.println(line);
        }
      }
    }
  }

  static final class Result {
    long[] latenciesNanos;
    long errors;
    double seconds;
  }

  static Result run(URL url, int connections, double seconds) throws InterruptedException {
    AtomicBoolean stop = new AtomicBoolean();
    Worker[] workers = new Worker[connections];
    Thread[] threads = new Thread[connections];
    for (int i = 0; i < connections; i++) {
      workers[i] = new Worker(url, stop);
      threads[i] = new Thread(workers[i], "loadgen-" + i);
    }
    long start = System.nanoTime();
    for (Thread t : threads) {
      t.start();
    }
    Thread.sleep((long) (seconds * 1000));
    stop.set(true);
    for (Thread t : threads) {
      t.join();
    }
    long end = System.nanoTime();

    Result r = new Result();
    r.seconds = (end - start) / 1e9;
    int total = 0;
    for (Worker w : workers) {
      total += w.count;
      r.errors += w.errors;
    }
    r.latenciesNanos = new long[total];
    int off = 0;
    for (Worker w : workers) {
      System.arraycopy(w.samples, 0, r.latenciesNanos, off, w.count);
      off += w.count;
    }
    Arrays.sort(r.latenciesNanos);
    return r;
  }

  static final class Worker implements Runnable {
    final URL url;
    final AtomicBoolean stop;
    long[] samples = new long[1 << 16];
    int count;
    long errors;

    Worker(URL url, AtomicBoolean stop) {
      this.url = url;
      this.stop = stop;
    }

    @Override
    public void run() {
      byte[] buf = new byte[8192];
      while (!stop.get()) {
        long t0 = System.nanoTime();
        boolean ok;
        try {
          ok = Http.get(url, buf, 2000, 10000) == 200;
        } catch (IOException e) {
          ok = false;
        }
        long dt = System.nanoTime() - t0;
        if (!ok) {
          errors++;
          continue;
        }
        if (count == samples.length) {
          samples = Arrays.copyOf(samples, samples.length * 2);
        }
        samples[count++] = dt;
      }
    }
  }

  static double pct(long[] sorted, double p) {
    if (sorted.length == 0) {
      return 0;
    }
    int idx = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
    return sorted[Math.max(0, Math.min(sorted.length - 1, idx))];
  }

  static String fmt(double d) {
    return String.format(Locale.ROOT, "%.3f", d);
  }

  static final class Args {
    final String command;
    final List<String> rest = new ArrayList<>();
    final java.util.Map<String, String> opts = new java.util.HashMap<>();

    Args(String[] args) {
      command = args.length > 0 ? args[0] : "";
      for (int i = 1; i < args.length; i++) {
        if ("--".equals(args[i])) {
          rest.addAll(Arrays.asList(args).subList(i + 1, args.length));
          break;
        }
        if (args[i].startsWith("--") && i + 1 < args.length) {
          opts.put(args[i].substring(2), args[++i]);
        }
      }
    }

    String get(String k, String def) {
      return opts.getOrDefault(k, def);
    }

    double getDouble(String k, double def) {
      String v = opts.get(k);
      return v == null ? def : Double.parseDouble(v);
    }
  }
}
