package datadog.trace.agent.test.asserts

import datadog.trace.core.DDSpan
import groovy.transform.stc.ClosureParams
import groovy.transform.stc.SimpleType

import java.util.concurrent.atomic.AtomicInteger

import static SpanAssert.assertSpan

class TraceAssert {
  private final List<DDSpan> trace
  private final int size
  private final Set<Integer> assertedIndexes = new HashSet<>()
  private final AtomicInteger spanAssertCount = new AtomicInteger(0)

  private TraceAssert(trace) {
    this.trace = trace
    size = trace.size()
  }

  private static final NAME_COMPARATOR = new Comparator<DDSpan>() {
    @Override
    int compare(DDSpan o1, DDSpan o2) {
      return o1.spanName.toString() <=> o2.spanName.toString()
    }
  }

  static void assertTrace(List<DDSpan> trace, int expectedSize,
                          @ClosureParams(value = SimpleType, options = ['datadog.trace.agent.test.asserts.TraceAssert'])
                          @DelegatesTo(value = TraceAssert, strategy = Closure.DELEGATE_FIRST) Closure spec) {
    assertTrace(trace, expectedSize, false, spec)
  }

  static void assertTrace(List<DDSpan> trace, int expectedSize, boolean sortByName,
                          @ClosureParams(value = SimpleType, options = ['datadog.trace.agent.test.asserts.TraceAssert'])
                          @DelegatesTo(value = TraceAssert, strategy = Closure.DELEGATE_FIRST) Closure spec) {
    assert trace.size() == expectedSize
    if (sortByName) {
      def sorted = new ArrayList<DDSpan>(trace)
      Collections.sort(sorted, NAME_COMPARATOR)
      trace = sorted
    }
    def asserter = new TraceAssert(trace)
    def clone = (Closure) spec.clone()
    clone.delegate = asserter
    clone.resolveStrategy = Closure.DELEGATE_FIRST
    clone(asserter)
    asserter.assertSpansAllVerified()
  }

  DDSpan span(int index) {
    trace.get(index)
  }

  int nextSpanId() {
    return spanAssertCount.getAndIncrement()
  }

  void span(@ClosureParams(value = SimpleType, options = ['datadog.trace.agent.test.asserts.SpanAssert']) @DelegatesTo(value = SpanAssert, strategy = Closure.DELEGATE_FIRST) Closure spec) {
    span(nextSpanId(), spec)
  }

  void span(int index, @ClosureParams(value = SimpleType, options = ['datadog.trace.agent.test.asserts.SpanAssert']) @DelegatesTo(value = SpanAssert, strategy = Closure.DELEGATE_FIRST) Closure spec) {
    if (index >= size) {
      throw new ArrayIndexOutOfBoundsException(index)
    }
    if (trace.size() != size) {
      throw new ConcurrentModificationException("Trace modified during assertion")
    }
    assertedIndexes.add(index)
    if (index > 0) {
      assertSpan(trace.get(index), spec, trace.get(index - 1))
    } else {
      assertSpan(trace.get(index), spec)
    }

  }

  void assertSpansAllVerified() {
    assert assertedIndexes.size() == size
  }
}
