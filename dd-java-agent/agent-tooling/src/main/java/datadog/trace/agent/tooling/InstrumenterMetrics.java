package datadog.trace.agent.tooling;

import datadog.trace.api.Config;
import java.util.concurrent.atomic.AtomicLong;

public final class InstrumenterMetrics {

  // split long into count (max ~32 million) and elapsed (max ~9 minutes)
  private static final int COUNT_SHIFT = 39;
  private static final long COUNT_BIT = 1L << COUNT_SHIFT;
  private static final long NANOS_MASK = COUNT_BIT - 1;

  static final class Stats {
    static final AtomicLong matching = new AtomicLong();
    static final AtomicLong transforming = new AtomicLong();
    static final AtomicLong knownTypeHit = new AtomicLong();
    static final AtomicLong knownTypeMiss = new AtomicLong();
    static final AtomicLong typeHierarchyHit = new AtomicLong();
    static final AtomicLong typeHierarchyMiss = new AtomicLong();
    static final AtomicLong contextStoreHit = new AtomicLong();
    static final AtomicLong contextStoreMiss = new AtomicLong();
    static final AtomicLong narrowLocationHit = new AtomicLong();
    static final AtomicLong narrowLocationMiss = new AtomicLong();
    static final AtomicLong narrowTypeHit = new AtomicLong();
    static final AtomicLong narrowTypeMiss = new AtomicLong();
    static final AtomicLong buildTypeMemo = new AtomicLong();
    static final AtomicLong reuseTypeMemo = new AtomicLong();
    static final AtomicLong buildTypeOutline = new AtomicLong();
    static final AtomicLong reuseTypeOutline = new AtomicLong();
    static final AtomicLong buildFullType = new AtomicLong();
    static final AtomicLong reuseFullType = new AtomicLong();
    static final AtomicLong resolveClassFile = new AtomicLong();
    static final AtomicLong missingClassFile = new AtomicLong();
  }

  private static final boolean ENABLED = Config.get().isTriageEnabled();

  public static long tick() {
    if (ENABLED) {
      return System.nanoTime();
    } else {
      return 0;
    }
  }

  private static void record(AtomicLong stat, long fromTick) {
    if (ENABLED) {
      long oldValue, newValue;
      do {
        oldValue = stat.get();
        newValue = oldValue;
        if ((newValue | NANOS_MASK) != -1) {
          newValue += COUNT_BIT;
        }
        if ((newValue | ~NANOS_MASK) != -1) {
          newValue += Math.min(System.nanoTime() - fromTick, NANOS_MASK - (newValue & NANOS_MASK));
        }
      } while (!stat.compareAndSet(oldValue, newValue));
    }
  }

  public static void matchType(long fromTick) {
    if (ENABLED) {
      record(Stats.matching, fromTick);
    }
  }

  public static void transformType(long fromTick) {
    if (ENABLED) {
      record(Stats.transforming, fromTick);
    }
  }

  public static void knownTypeHit(long fromTick) {
    if (ENABLED) {
      record(Stats.knownTypeHit, fromTick);
    }
  }

  public static void knownTypeMiss(long fromTick) {
    if (ENABLED) {
      record(Stats.knownTypeMiss, fromTick);
    }
  }

  public static void typeHierarchyHit(long fromTick) {
    if (ENABLED) {
      record(Stats.typeHierarchyHit, fromTick);
    }
  }

  public static void typeHierarchyMiss(long fromTick) {
    if (ENABLED) {
      record(Stats.typeHierarchyMiss, fromTick);
    }
  }

  public static void contextStoreHit(long fromTick) {
    if (ENABLED) {
      record(Stats.contextStoreHit, fromTick);
    }
  }

  public static void contextStoreMiss(long fromTick) {
    if (ENABLED) {
      record(Stats.contextStoreMiss, fromTick);
    }
  }

  public static void narrowLocationHit(long fromTick) {
    if (ENABLED) {
      record(Stats.narrowLocationHit, fromTick);
    }
  }

  public static void narrowLocationMiss(long fromTick) {
    if (ENABLED) {
      record(Stats.narrowLocationMiss, fromTick);
    }
  }

  public static void narrowTypeHit(long fromTick) {
    if (ENABLED) {
      record(Stats.narrowTypeHit, fromTick);
    }
  }

  public static void narrowTypeMiss(long fromTick) {
    if (ENABLED) {
      record(Stats.narrowTypeMiss, fromTick);
    }
  }

  public static void buildTypeMemo(long fromTick) {
    if (ENABLED) {
      record(Stats.buildTypeMemo, fromTick);
    }
  }

  public static void reuseTypeMemo(long fromTick) {
    if (ENABLED) {
      record(Stats.reuseTypeMemo, fromTick);
    }
  }

  public static void buildTypeDescription(long fromTick, boolean isOutline) {
    if (ENABLED) {
      record(isOutline ? Stats.buildTypeOutline : Stats.buildFullType, fromTick);
    }
  }

  public static void reuseTypeDescription(long fromTick, boolean isOutline) {
    if (ENABLED) {
      record(isOutline ? Stats.reuseTypeOutline : Stats.reuseFullType, fromTick);
    }
  }

  public static void resolveClassFile(long fromTick) {
    if (ENABLED) {
      record(Stats.resolveClassFile, fromTick);
    }
  }

  public static void missingClassFile(long fromTick) {
    if (ENABLED) {
      record(Stats.missingClassFile, fromTick);
    }
  }

  public static String summary() {
    if (!ENABLED) {
      return "Set DD_TRACE_TRIAGE=true to collect instrumenter metrics during startup";
    }

    StringBuilder buf = new StringBuilder();

    buf.append("----------------------------------------------------------------\n");
    summarize(buf, "Matching:              ", Stats.matching);
    summarize(buf, "Transforming:          ", Stats.transforming);
    buf.append("----------------------------------------------------------------\n");
    summarize(buf, "Known type hit:        ", Stats.knownTypeHit);
    summarize(buf, "Known type miss:       ", Stats.knownTypeMiss);
    summarize(buf, "Type hierarchy hit:    ", Stats.typeHierarchyHit);
    summarize(buf, "Type hierarchy miss:   ", Stats.typeHierarchyMiss);
    summarize(buf, "Context store hit:     ", Stats.contextStoreHit);
    summarize(buf, "Context store miss:    ", Stats.contextStoreMiss);
    summarize(buf, "Narrow location hit:   ", Stats.narrowLocationHit);
    summarize(buf, "Narrow location miss:  ", Stats.narrowLocationMiss);
    summarize(buf, "Narrow type hit:       ", Stats.narrowTypeHit);
    summarize(buf, "Narrow type miss:      ", Stats.narrowTypeMiss);
    buf.append("----------------------------------------------------------------\n");
    summarize(buf, "Build type-memo:       ", Stats.buildTypeMemo);
    summarize(buf, "Reuse type-memo:       ", Stats.reuseTypeMemo);
    summarize(buf, "Build type-outline:    ", Stats.buildTypeOutline);
    summarize(buf, "Reuse type-outline:    ", Stats.reuseTypeOutline);
    summarize(buf, "Build full-type:       ", Stats.buildFullType);
    summarize(buf, "Reuse full-type:       ", Stats.reuseFullType);
    summarize(buf, "Resolve class-file:    ", Stats.resolveClassFile);
    summarize(buf, "Missing class-file:    ", Stats.missingClassFile);
    buf.append("----------------------------------------------------------------");

    return buf.toString();
  }

  private static void summarize(StringBuilder buf, String prefix, AtomicLong stat) {
    long value = stat.get();
    buf.append(prefix)
        .append(String.format("%-12d", value >>> COUNT_SHIFT))
        .append(" ")
        .append(String.format("%.1f", (value & NANOS_MASK) / 1_000_000.0))
        .append(" ms\n");
  }
}
