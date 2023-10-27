package datadog.trace.api.metrics;

import datadog.trace.api.Platform;
import datadog.trace.util.AgentTaskScheduler;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLongArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class InstrumentationMetrics {

  static {
    if (!Platform.isNativeImageBuilder()) {
      AgentTaskScheduler.INSTANCE.scheduleAtFixedRate(
          InstrumentationMetrics::logSummary, 2, 2, TimeUnit.MINUTES);
    }
  }

  private static final Logger log = LoggerFactory.getLogger(InstrumentationMetrics.class);

  private static final int MATCHING = 0;
  private static final int TRANSFORMING = 1;
  private static final int KNOWN_TYPE_HIT = 2;
  private static final int KNOWN_TYPE_MISS = 3;
  private static final int CLASS_LOADER_HIT = 4;
  private static final int CLASS_LOADER_MISS = 5;
  private static final int TYPE_HIERARCHY_HIT = 6;
  private static final int TYPE_HIERARCHY_MISS = 7;
  private static final int CONTEXT_STORE_HIT = 8;
  private static final int CONTEXT_STORE_MISS = 9;
  private static final int NARROW_LOCATION_HIT = 10;
  private static final int NARROW_LOCATION_MISS = 11;
  private static final int NARROW_TYPE_HIT = 12;
  private static final int NARROW_TYPE_MISS = 13;
  private static final int BUILD_TYPE_MEMO = 14;
  private static final int REUSE_TYPE_MEMO = 15;
  private static final int BUILD_TYPE_OUTLINE = 16;
  private static final int REUSE_TYPE_OUTLINE = 17;
  private static final int BUILD_FULL_TYPE = 18;
  private static final int REUSE_FULL_TYPE = 19;
  private static final int RESOLVE_CLASS_FILE = 20;
  private static final int MISSING_CLASS_FILE = 21;

  private static final AtomicIntegerArray count = new AtomicIntegerArray(22);
  private static final AtomicLongArray elapsed = new AtomicLongArray(22);

  public static void matchType(long startNanos) {
    record(MATCHING, startNanos);
  }

  public static void transformType(long startNanos) {
    record(TRANSFORMING, startNanos);
  }

  public static void knownTypeHit(long startNanos) {
    record(KNOWN_TYPE_HIT, startNanos);
  }

  public static void knownTypeMiss(long startNanos) {
    record(KNOWN_TYPE_MISS, startNanos);
  }

  public static void classLoaderHit(long startNanos) {
    record(CLASS_LOADER_HIT, startNanos);
  }

  public static void classLoaderMiss(long startNanos) {
    record(CLASS_LOADER_MISS, startNanos);
  }

  public static void typeHierarchyHit(long startNanos) {
    record(TYPE_HIERARCHY_HIT, startNanos);
  }

  public static void typeHierarchyMiss(long startNanos) {
    record(TYPE_HIERARCHY_MISS, startNanos);
  }

  public static void contextStoreHit(long startNanos) {
    record(CONTEXT_STORE_HIT, startNanos);
  }

  public static void contextStoreMiss(long startNanos) {
    record(CONTEXT_STORE_MISS, startNanos);
  }

  public static void narrowLocationHit(long startNanos) {
    record(NARROW_LOCATION_HIT, startNanos);
  }

  public static void narrowLocationMiss(long startNanos) {
    record(NARROW_LOCATION_MISS, startNanos);
  }

  public static void narrowTypeHit(long startNanos) {
    record(NARROW_TYPE_HIT, startNanos);
  }

  public static void narrowTypeMiss(long startNanos) {
    record(NARROW_TYPE_MISS, startNanos);
  }

  public static void buildTypeMemo(long startNanos) {
    record(BUILD_TYPE_MEMO, startNanos);
  }

  public static void reuseTypeMemo(long startNanos) {
    record(REUSE_TYPE_MEMO, startNanos);
  }

  public static void buildTypeOutline(long startNanos) {
    record(BUILD_TYPE_OUTLINE, startNanos);
  }

  public static void reuseTypeOutline(long startNanos) {
    record(REUSE_TYPE_OUTLINE, startNanos);
  }

  public static void buildFullType(long startNanos) {
    record(BUILD_FULL_TYPE, startNanos);
  }

  public static void reuseFullType(long startNanos) {
    record(REUSE_FULL_TYPE, startNanos);
  }

  public static void resolveClassFile(long startNanos) {
    record(RESOLVE_CLASS_FILE, startNanos);
  }

  public static void missingClassFile(long startNanos) {
    record(MISSING_CLASS_FILE, startNanos);
  }

  private static void record(int id, long startNanos) {
    elapsed.getAndAdd(id, System.nanoTime() - startNanos);
    count.getAndIncrement(id);
  }

  public static void logSummary() {
    StringBuilder buf = new StringBuilder();

    buf.append("----------------------------------------------------------------\n");
    summarize(buf, "Matching:              ", MATCHING);
    summarize(buf, "Transforming:          ", TRANSFORMING);
    buf.append("----------------------------------------------------------------\n");
    summarize(buf, "Known type hit:        ", KNOWN_TYPE_HIT);
    summarize(buf, "Known type miss:       ", KNOWN_TYPE_MISS);
    summarize(buf, "Class loader hit:      ", CLASS_LOADER_HIT);
    summarize(buf, "Class loader miss:     ", CLASS_LOADER_MISS);
    summarize(buf, "Type hierarchy hit:    ", TYPE_HIERARCHY_HIT);
    summarize(buf, "Type hierarchy miss:   ", TYPE_HIERARCHY_MISS);
    summarize(buf, "Context store hit:     ", CONTEXT_STORE_HIT);
    summarize(buf, "Context store miss:    ", CONTEXT_STORE_MISS);
    summarize(buf, "Narrow location hit:   ", NARROW_LOCATION_HIT);
    summarize(buf, "Narrow location miss:  ", NARROW_LOCATION_MISS);
    summarize(buf, "Narrow type hit:       ", NARROW_TYPE_HIT);
    summarize(buf, "Narrow type miss:      ", NARROW_TYPE_MISS);
    buf.append("----------------------------------------------------------------\n");
    summarize(buf, "Build type-memo:       ", BUILD_TYPE_MEMO);
    summarize(buf, "Reuse type-memo:       ", REUSE_TYPE_MEMO);
    summarize(buf, "Build type-outline:    ", BUILD_TYPE_OUTLINE);
    summarize(buf, "Reuse type-outline:    ", REUSE_TYPE_OUTLINE);
    summarize(buf, "Build full-type:       ", BUILD_FULL_TYPE);
    summarize(buf, "Reuse full-type:       ", REUSE_FULL_TYPE);
    summarize(buf, "Resolve class-file:    ", RESOLVE_CLASS_FILE);
    summarize(buf, "Missing class-file:    ", MISSING_CLASS_FILE);
    buf.append("----------------------------------------------------------------");

    log.info("\n{}", buf);
  }

  private static void summarize(StringBuilder buf, String prefix, int id) {
    buf.append(prefix)
        .append(String.format("%-12d", count.get(id)))
        .append(" ns=")
        .append(elapsed.get(id))
        .append("\n");
  }
}
