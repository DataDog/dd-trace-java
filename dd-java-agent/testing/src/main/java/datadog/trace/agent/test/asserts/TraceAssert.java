package datadog.trace.agent.test.asserts;

import static datadog.trace.agent.test.asserts.SpanAssert.assertSpan;

import datadog.trace.core.DDSpan;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class TraceAssert {

  private List<DDSpan> trace;
  private final int size;
  private final Set<Integer> assertedIndexes = new HashSet<>();
  private final AtomicInteger spanAssertCount = new AtomicInteger(0);

  private TraceAssert(List<DDSpan> trace) {
    this.trace = Collections.unmodifiableList(trace);
    this.size = trace.size();
  }

  public static final Comparator<DDSpan> NAME_COMPARATOR =
      new Comparator<DDSpan>() {
        @Override
        public int compare(DDSpan o1, DDSpan o2) {
          int compare = o1.getSpanName().toString().compareTo(o2.getSpanName().toString());
          return compare != 0
              ? compare
              : String.valueOf(o1.getResourceName())
                  .compareTo(String.valueOf(o2.getResourceName()));
        }
      };

  public static void assertTrace(List<DDSpan> trace, int expectedSize, Consumer<TraceAssert> spec) {
    assertTrace(trace, expectedSize, null, spec);
  }

  public static void assertTrace(
      List<DDSpan> trace, int expectedSize, Comparator<DDSpan> sorter, Consumer<TraceAssert> spec) {

    // Copy to avoid concurrent modification with external code.
    List<DDSpan> copy = new ArrayList<>(trace);
    if (copy.size() != expectedSize) {
      throw new AssertionError("Expected " + expectedSize + " spans but got " + copy.size());
    }
    if (sorter != null) {
      copy.sort(sorter);
    }

    TraceAssert asserter = new TraceAssert(copy);
    spec.accept(asserter);
    asserter.assertSpansAllVerified();
  }

  public DDSpan span(int index) {
    return trace.get(index);
  }

  public int nextSpanId() {
    return spanAssertCount.getAndIncrement();
  }

  public void span(Consumer<SpanAssert> spec) {
    span(nextSpanId(), spec);
  }

  // Groovy-friendly overload: allow a Closure and set its delegate to SpanAssert
  public void span(groovy.lang.Closure<?> spec) {
    span(
        (Consumer<SpanAssert>)
            (asserter) -> {
              spec.setDelegate(asserter);
              spec.setResolveStrategy(groovy.lang.Closure.DELEGATE_FIRST);
              spec.call(asserter);
            });
  }

  public void span(int index, Consumer<SpanAssert> spec) {
    if (index >= size) {
      throw new ArrayIndexOutOfBoundsException(index);
    }
    if (trace.size() != size) {
      throw new ConcurrentModificationException("Trace modified during assertion");
    }
    assertedIndexes.add(index);

    if (index > 0) {
      assertSpan(trace.get(index), spec, trace.get(index - 1));
    } else {
      assertSpan(trace.get(index), spec);
    }
  }

  // Groovy-friendly overload with explicit index
  public void span(int index, groovy.lang.Closure<?> spec) {
    span(
        index,
        (Consumer<SpanAssert>)
            (asserter) -> {
              spec.setDelegate(asserter);
              spec.setResolveStrategy(groovy.lang.Closure.DELEGATE_FIRST);
              spec.call(asserter);
            });
  }

  public void assertSpansAllVerified() {
    if (assertedIndexes.size() != size) {
      throw new AssertionError(
          "Not all spans were verified. Expected "
              + size
              + " but verified "
              + assertedIndexes.size());
    }
  }

  public void sortSpansByStart() {
    List<DDSpan> sorted = new ArrayList<>(trace);
    sorted.sort(Comparator.comparingLong(DDSpan::getStartTimeNano));
    this.trace = Collections.unmodifiableList(sorted);
  }
}
