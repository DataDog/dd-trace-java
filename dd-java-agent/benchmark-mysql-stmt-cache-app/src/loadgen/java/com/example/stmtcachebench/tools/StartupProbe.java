package com.example.stmtcachebench.tools;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public final class StartupProbe {

  public static void main(String[] args) throws Exception {
    LoadGen.Args a = new LoadGen.Args(prepend(args));
    URL url = new URL(a.get("url", "http://127.0.0.1:18080/nodb"));
    String config = a.get("config", "unknown");
    int runs = (int) a.getDouble("runs", 5);
    int discard = (int) a.getDouble("discard", 1);
    double timeout = a.getDouble("timeout", 120);
    String log = a.get("log", null);
    String out = a.get("out", null);
    if (a.rest.isEmpty()) {
      throw new IllegalArgumentException("missing server command after --");
    }
    if (Http.ready(url)) {
      throw new IllegalStateException(url + " already answers before the server started");
    }

    for (int run = 1 - discard; run <= runs; run++) {
      ProcessBuilder pb = new ProcessBuilder(a.rest).redirectErrorStream(true);
      if (log != null) {
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(new File(log)));
      } else {
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
      }
      long t0 = System.nanoTime();
      Process p = pb.start();
      long deadline = t0 + (long) (timeout * 1e9);
      double ms = -1;
      try {
        while (System.nanoTime() < deadline) {
          if (!p.isAlive()) {
            throw new IllegalStateException("server exited with " + p.exitValue() + "; see " + log);
          }
          if (Http.ready(url)) {
            ms = (System.nanoTime() - t0) / 1e6;
            break;
          }
          Thread.sleep(5);
        }
      } finally {
        p.destroy();
        if (!p.waitFor(15, TimeUnit.SECONDS)) {
          p.destroyForcibly().waitFor();
        }
      }
      if (ms < 0) {
        throw new IllegalStateException("server not ready within " + timeout + "s; see " + log);
      }
      long portDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (Http.ready(url) && System.nanoTime() < portDeadline) {
        Thread.sleep(50);
      }
      if (run < 1) {
        System.out.printf(Locale.ROOT, "  %-10s startup discarded run: %.1f ms%n", config, ms);
        continue;
      }
      System.out.printf(Locale.ROOT, "  %-10s startup run %d: %.1f ms%n", config, run, ms);
      if (out != null) {
        try (PrintWriter w = new PrintWriter(new FileWriter(out, true))) {
          w.println(String.join("\t", "startup", config, Integer.toString(run), LoadGen.fmt(ms)));
        }
      }
    }
  }

  private static String[] prepend(String[] args) {
    String[] r = new String[args.length + 1];
    r[0] = "startup";
    System.arraycopy(args, 0, r, 1, args.length);
    return r;
  }
}
