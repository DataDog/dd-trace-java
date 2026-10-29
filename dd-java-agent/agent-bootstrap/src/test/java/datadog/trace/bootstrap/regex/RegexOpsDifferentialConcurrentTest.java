package datadog.trace.bootstrap.regex;

import static datadog.trace.bootstrap.regex.RegexOpsDifferentialSupport.copy;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import datadog.trace.bootstrap.regex.RegexOpsDifferentialSupport.Op;
import datadog.trace.bootstrap.regex.RegexOpsDifferentialSupport.Outcome;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

@SuppressForbidden
class RegexOpsDifferentialConcurrentTest {

  private static final int THREADS = 16;
  private static final int ITERATIONS_PER_THREAD = 20_000;

  private static final class Case {
    final String label;
    final String regex;
    final Op ops;
    final Outcome expected;

    Case(String label, String regex, Op jdk, Op ops) {
      this.label = label;
      this.regex = regex;
      this.ops = ops;
      this.expected = Outcome.of(jdk, regex);
    }
  }

  @Test
  void concurrentCallsMatchPrecomputedJdkResults() throws Exception {
    String[] regexes = {
      new String(","),
      new String("\\s*,\\s*"),
      new String("(\\w)(\\d)"),
      new String("(?i)ab"),
      new String("[;|]"),
      new String("a"),
      new String("\\."),
      new String("(?<g>b+)"),
      new String("("),
      new String("(?<=a)"),
    };
    int[] flags = {0, Pattern.CASE_INSENSITIVE, Pattern.LITERAL, Pattern.COMMENTS | Pattern.DOTALL};
    String[] inputs = {"a1, b2 ,c3;AB|ab", "", "aaa.bbb.ab", "x,,y,"};

    List<Case> cases = new ArrayList<>();
    for (String regex : regexes) {
      for (int f : flags) {
        cases.add(
            new Case(
                "compile flags=" + f,
                regex,
                r -> Pattern.compile(r, f),
                r -> RegexOps.compile(r, f)));
      }
      cases.add(new Case("compile", regex, Pattern::compile, RegexOps::compile));
      for (String in : inputs) {
        cases.add(
            new Case(
                "patternMatches",
                regex,
                r -> Pattern.matches(r, in),
                r -> RegexOps.patternMatches(r, in)));
        cases.add(new Case("matches", regex, in::matches, r -> RegexOps.matches(in, r)));
        cases.add(new Case("split", regex, in::split, r -> RegexOps.split(in, r)));
        cases.add(
            new Case("split,-1", regex, r -> in.split(r, -1), r -> RegexOps.split(in, r, -1)));
        cases.add(
            new Case(
                "replaceAll",
                regex,
                r -> in.replaceAll(r, "<$0>"),
                r -> RegexOps.replaceAll(in, r, "<$0>")));
        cases.add(
            new Case(
                "replaceFirst",
                regex,
                r -> in.replaceFirst(r, "$1"),
                r -> RegexOps.replaceFirst(in, r, "$1")));
      }
    }

    Queue<String> failures = new ConcurrentLinkedQueue<>();
    AtomicLong comparisons = new AtomicLong();
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(THREADS);
    try {
      List<Future<?>> futures = new ArrayList<>();
      for (int t = 0; t < THREADS; t++) {
        long seed = 1000L + t;
        futures.add(
            pool.submit(
                () -> {
                  Random rnd = new Random(seed);
                  start.await();
                  for (int i = 0; i < ITERATIONS_PER_THREAD; i++) {
                    Case c = cases.get(rnd.nextInt(cases.size()));
                    int mode = rnd.nextInt(10);
                    String arg = mode == 0 ? copy(c.regex) : c.regex;
                    Outcome actual = Outcome.of(c.ops, arg);
                    comparisons.incrementAndGet();
                    if (!c.expected.equals(actual) && failures.size() < 100) {
                      failures.add(
                          c.label
                              + " regex="
                              + RegexOpsDifferentialSupport.quote(c.regex)
                              + (mode == 0 ? " (copy)" : " (same-ref)")
                              + ": JDK "
                              + c.expected
                              + " but RegexOps "
                              + actual);
                    }
                    if (mode == 1) {
                      String churn = "c" + rnd.nextInt(5_000) + "|" + c.regex.length();
                      RegexOps.compile(churn);
                      Pattern p = RegexOps.compile(churn);
                      if (!churn.equals(p.pattern()) || p.flags() != 0) {
                        failures.add("churn compile returned " + p.pattern() + " for " + churn);
                      }
                    }
                  }
                  return null;
                }));
      }
      start.countDown();
      for (Future<?> f : futures) {
        f.get(120, SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }

    if (!failures.isEmpty()) {
      fail(failures.size() + " concurrent divergence(s):\n  " + String.join("\n  ", failures));
    }
    assertEquals((long) THREADS * ITERATIONS_PER_THREAD, comparisons.get());
    assertTrue(cases.size() > 100);
  }
}
