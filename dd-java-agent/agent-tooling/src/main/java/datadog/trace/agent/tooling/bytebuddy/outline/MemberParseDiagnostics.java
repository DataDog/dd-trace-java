package datadog.trace.agent.tooling.bytebuddy.outline;

import datadog.trace.api.InstrumenterConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Prototype diagnostics (only with {@code dd.resolver.declared-method-sieve.verify}): counts the
 * call sites that force lazy outline members to be parsed, and logs the most common ones at exit.
 */
public final class MemberParseDiagnostics {
  private static final Logger log = LoggerFactory.getLogger(MemberParseDiagnostics.class);

  static final boolean ENABLED = InstrumenterConfig.get().isResolverDeclaredMethodSieveVerify();

  private static final Map<String, AtomicInteger> callers = new ConcurrentHashMap<>();
  private static final Map<String, AtomicInteger> counters = new ConcurrentHashMap<>();

  static {
    if (ENABLED) {
      Runtime.getRuntime()
          .addShutdownHook(new Thread(MemberParseDiagnostics::report, "dd-sieve-diagnostics"));
    }
  }

  private MemberParseDiagnostics() {}

  /** Records the caller that needed members, skipping outline and byte-buddy frames. */
  static void recordParse() {
    StackTraceElement[] stack = new Throwable().getStackTrace();
    StringBuilder key = new StringBuilder();
    int kept = 0;
    for (StackTraceElement frame : stack) {
      String cls = frame.getClassName();
      if (cls.startsWith("datadog.trace.agent.tooling.bytebuddy.outline.")
          || cls.startsWith("net.bytebuddy.description.")) {
        continue;
      }
      key.append(cls.substring(cls.lastIndexOf('.') + 1))
          .append('.')
          .append(frame.getMethodName())
          .append(':')
          .append(frame.getLineNumber())
          .append(" < ");
      if (++kept == 4) {
        break;
      }
    }
    callers.computeIfAbsent(key.toString(), k -> new AtomicInteger()).incrementAndGet();
  }

  /** Counts a named event, e.g. which member matcher passed the sieve. */
  public static void count(String event) {
    if (ENABLED) {
      counters.computeIfAbsent(event, k -> new AtomicInteger()).incrementAndGet();
    }
  }

  public static boolean enabled() {
    return ENABLED;
  }

  private static void report() {
    log.warn("Sieve diagnostics: member parses by caller (top 15):");
    for (String line : top(callers, 15)) {
      log.warn("  {}", line);
    }
    log.warn("Sieve diagnostics: events (top 40):");
    for (String line : top(counters, 40)) {
      log.warn("  {}", line);
    }
  }

  private static List<String> top(Map<String, AtomicInteger> counts, int limit) {
    List<Map.Entry<String, AtomicInteger>> entries = new ArrayList<>(counts.entrySet());
    entries.sort((a, b) -> b.getValue().get() - a.getValue().get());
    List<String> lines = new ArrayList<>();
    for (int i = 0; i < Math.min(limit, entries.size()); i++) {
      lines.add(entries.get(i).getValue().get() + "  " + entries.get(i).getKey());
    }
    return lines;
  }
}
