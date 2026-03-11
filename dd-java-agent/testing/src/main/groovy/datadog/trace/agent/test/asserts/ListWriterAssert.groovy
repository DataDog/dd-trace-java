package datadog.trace.agent.test.asserts

import static TraceAssert.assertTrace

import datadog.trace.common.writer.ListWriter
import datadog.trace.core.DDSpan
import groovy.transform.stc.ClosureParams
import groovy.transform.stc.SimpleType
import org.codehaus.groovy.runtime.powerassert.PowerAssertionError
import org.spockframework.runtime.Condition
import org.spockframework.runtime.ConditionNotSatisfiedError
import org.spockframework.runtime.model.TextPosition

import java.util.concurrent.atomic.AtomicInteger

class ListWriterAssert {
  public static final Comparator<List<DDSpan>> SORT_TRACES_BY_ID = new SortTracesById()
  public static final Comparator<List<DDSpan>> SORT_TRACES_BY_START = new SortTracesByStart()
  public static final Comparator<List<DDSpan>> SORT_TRACES_BY_NAMES = new SortTracesByNames()

  private List<List<DDSpan>> traces
  private final int size
  private final Set<Integer> assertedIndexes = new HashSet<>()
  private final AtomicInteger traceAssertCount = new AtomicInteger(0)

  private ListWriterAssert(List<List<DDSpan>> traces) {
    this.traces = traces
    size = traces.size()
  }

  static void assertTraces(ListWriter writer, int expectedSize,
    @ClosureParams(value = SimpleType, options = ['datadog.trace.agent.test.asserts.ListWriterAssert'])
    @DelegatesTo(value = ListWriterAssert, strategy = Closure.DELEGATE_FIRST) Closure spec) {
    assertTraces(writer, expectedSize, false, spec)
  }

  static void assertTraces(ListWriter writer, int expectedSize,
    boolean ignoreAdditionalTraces,
    @ClosureParams(value = SimpleType, options = ['datadog.trace.agent.test.asserts.ListWriterAssert'])
    @DelegatesTo(value = ListWriterAssert, strategy = Closure.DELEGATE_FIRST) Closure spec) {
    assertTraces(writer, expectedSize, ignoreAdditionalTraces, SORT_TRACES_BY_START, spec)
  }

  static void assertTraces(ListWriter writer, int expectedSize,
    boolean ignoreAdditionalTraces,
    Comparator<List<DDSpan>> traceSorter,
    @ClosureParams(value = SimpleType, options = ['datadog.trace.agent.test.asserts.ListWriterAssert'])
    @DelegatesTo(value = ListWriterAssert, strategy = Closure.DELEGATE_FIRST) Closure spec) {
    try {
      writer.waitForTraces(expectedSize)
      def array = writer.toArray()
      assert array.length == expectedSize
      def traces = (Arrays.asList(array) as List<List<DDSpan>>)
      Collections.sort(traces, traceSorter)
      def asserter = new ListWriterAssert(traces)
      def clone = (Closure) spec.clone()
      clone.delegate = asserter
      clone.resolveStrategy = Closure.DELEGATE_FIRST
      clone(asserter)
      if (!ignoreAdditionalTraces) {
        asserter.assertTracesAllVerified()
        if (writer.size() > traces.size()) {
          def extras = new ArrayList<>(writer)
          extras.removeAll(traces)
          def message = new StringBuilder("ListWriter obtained ${extras.size()} additional traces while validating:")
          extras.each {
            message.append('\n')
            message.append(it)
          }
          message.append('\n')
          throw new AssertionError(message)
        }
      }
    } catch (PowerAssertionError e) {
      def stackLine = null
      for (int i = 0; i < e.stackTrace.length; i++) {
        def className = e.stackTrace[i].className
        def skip = className.startsWith("org.codehaus.groovy.") ||
          className.startsWith("datadog.trace.agent.test.") ||
          className.startsWith("sun.reflect.") ||
          className.startsWith("groovy.lang.") ||
          className.startsWith("java.lang.")
        if (skip) {
          continue
        }
        stackLine = e.stackTrace[i]
        break
      }
      def condition = new Condition(null, "$stackLine", TextPosition.create(stackLine == null ? 0 : stackLine.lineNumber, 0), e.message, null, e)
      throw new ConditionNotSatisfiedError(condition, e)
    }
  }

  void sortSpansByStart() {
    traces = traces.collect {
      return new ArrayList<DDSpan>(it).sort { a, b ->
        return a.startTimeNano <=> b.startTimeNano
      }
    }
  }

  List<DDSpan> trace(int index) {
    return Collections.unmodifiableList(traces.get(index))
  }

  void trace(int expectedSize,
    @ClosureParams(value = SimpleType, options = ['datadog.trace.agent.test.asserts.TraceAssert'])
    @DelegatesTo(value = TraceAssert, strategy = Closure.DELEGATE_FIRST) Closure spec) {
    trace(expectedSize, false, spec)
  }
  void trace(int expectedSize, boolean sortByName,
    @ClosureParams(value = SimpleType, options = ['datadog.trace.agent.test.asserts.TraceAssert'])
    @DelegatesTo(value = TraceAssert, strategy = Closure.DELEGATE_FIRST) Closure spec) {
    trace(expectedSize, sortByName ? TraceAssert.NAME_COMPARATOR : null, spec)
  }

  void trace(int expectedSize, Comparator<DDSpan> sorter,
    @ClosureParams(value = SimpleType, options = ['datadog.trace.agent.test.asserts.TraceAssert'])
    @DelegatesTo(value = TraceAssert, strategy = Closure.DELEGATE_FIRST) Closure spec) {
    def index = traceAssertCount.getAndIncrement()

    if (index >= size) {
      throw new ArrayIndexOutOfBoundsException(index)
    }
    if (traces.size() != size) {
      throw new ConcurrentModificationException("ListWriter modified during assertion")
    }
    assertedIndexes.add(index)
    assertTrace(trace(index), expectedSize, sorter, spec)
  }

  void assertTracesAllVerified() {
    assert assertedIndexes.size() == size
  }

  private static class SortTracesByStart implements Comparator<List<DDSpan>> {
    @Override
    int compare(List<DDSpan> o1, List<DDSpan> o2) {
      return Long.compare(traceStart(o1), traceStart(o2))
    }

    long traceStart(List<DDSpan> trace) {
      assert !trace.isEmpty()
      return trace.get(0).localRootSpan.startTime
    }
  }

  private static class SortTracesById implements Comparator<List<DDSpan>> {
    @Override
    int compare(List<DDSpan> o1, List<DDSpan> o2) {
      return Long.compare(rootSpanId(o1), rootSpanId(o2))
    }

    long rootSpanId(List<DDSpan> trace) {
      assert !trace.isEmpty()
      return trace.get(0).localRootSpan.spanId.toLong()
    }
  }

  private static class SortTracesByNames implements Comparator<List<DDSpan>> {
    @Override
    int compare(List<DDSpan> o1, List<DDSpan> o2) {
      return rootSpanTrace(o1) <=> rootSpanTrace(o2)
    }

    String rootSpanTrace(List<DDSpan> trace) {
      assert !trace.isEmpty()
      def rootSpan = trace.get(0).localRootSpan
      return "${rootSpan.serviceName}/${rootSpan.operationName}/${rootSpan.resourceName}"
    }
  }
}
