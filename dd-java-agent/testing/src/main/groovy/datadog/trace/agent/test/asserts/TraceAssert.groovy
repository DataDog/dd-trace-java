package datadog.trace.agent.test.asserts

import datadog.trace.core.DDSpan
import groovy.transform.stc.ClosureParams
import groovy.transform.stc.SimpleType

import java.util.concurrent.atomic.AtomicInteger

import static SpanAssert.assertSpan

class TraceAssert {
  private List<DDSpan> trace
  private final int size
  private final Set<Integer> assertedIndexes = new HashSet<>()
  private final AtomicInteger spanAssertCount = new AtomicInteger(0)

  private TraceAssert(trace) {
    this.trace = Collections.unmodifiableList(trace)
    size = trace.size()
  }

  static final NAME_COMPARATOR = new Comparator<DDSpan>() {
    @Override
    int compare(DDSpan o1, DDSpan o2) {
      int compare = o1.spanName.toString() <=> o2.spanName.toString()
      return compare != 0 ? compare : String.valueOf(o1.resourceName) <=> String.valueOf(o2.resourceName)
    }
  }

  static void assertTrace(List<DDSpan> trace, int expectedSize,
    @ClosureParams(value = SimpleType, options = ['datadog.trace.agent.test.asserts.TraceAssert'])
    @DelegatesTo(value = TraceAssert, strategy = Closure.DELEGATE_FIRST) Closure spec) {
    assertTrace(trace, expectedSize, null, spec)
  }

  static void assertTrace(List<DDSpan> trace, int expectedSize, Comparator<DDSpan> sorter,
    @ClosureParams(value = SimpleType, options = ['datadog.trace.agent.test.asserts.TraceAssert'])
    @DelegatesTo(value = TraceAssert, strategy = Closure.DELEGATE_FIRST) Closure spec) {
    // Some tests do their own sorting of the spans which can happen concurrently with other code doing
    // iterations, so we make a copy of the list here to not cause a ConcurrentModificationException
    trace = new ArrayList<DDSpan>(trace)
    assert trace.size() == expectedSize
    if (sorter != null) {
      Collections.sort(trace, sorter)
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

  void sortSpansByStart() {
    trace = Collections.unmodifiableList(new ArrayList(trace).sort { a, b ->
      return a.startTimeNano <=> b.startTimeNano
    })
  }
}
