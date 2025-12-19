package datadog.trace.agent.test.asserts;

import static datadog.trace.agent.test.asserts.TraceAssert.assertTrace;

import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.DDSpan;
import java.util.*;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.codehaus.groovy.runtime.powerassert.PowerAssertionError;
import org.spockframework.runtime.Condition;
import org.spockframework.runtime.ConditionNotSatisfiedError;
import org.spockframework.runtime.model.TextPosition;

public class ListWriterAssert {

  public static final Comparator<List<DDSpan>> SORT_TRACES_BY_ID = new SortTracesById();
  public static final Comparator<List<DDSpan>> SORT_TRACES_BY_START = new SortTracesByStart();
  public static final Comparator<List<DDSpan>> SORT_TRACES_BY_NAMES = new SortTracesByNames();

  private List<List<DDSpan>> traces;
  private final int size;
  private final Set<Integer> assertedIndexes = new HashSet<>();
  private final AtomicInteger traceAssertCount = new AtomicInteger(0);

  private ListWriterAssert(List<List<DDSpan>> traces) {
    this.traces = traces;
    this.size = traces.size();
  }

  public static void assertTraces(
      ListWriter writer, int expectedSize, Consumer<ListWriterAssert> spec) {
    assertTraces(writer, expectedSize, false, SORT_TRACES_BY_START, spec);
  }

  public static void assertTraces(
      ListWriter writer,
      int expectedSize,
      boolean ignoreAdditionalTraces,
      Consumer<ListWriterAssert> spec) {
    assertTraces(writer, expectedSize, ignoreAdditionalTraces, SORT_TRACES_BY_START, spec);
  }

  public static void assertTraces(
      ListWriter writer,
      int expectedSize,
      boolean ignoreAdditionalTraces,
      Comparator<List<DDSpan>> traceSorter,
      Consumer<ListWriterAssert> spec) {

    try {
      writer.waitForTraces(expectedSize);
      Object[] array = writer.toArray();
      if (array.length != expectedSize) {
        throw new AssertionError("Expected " + expectedSize + " traces but got " + array.length);
      }

      List<List<DDSpan>> traces = new ArrayList<>();
      for (Object o : array) {
        traces.add((List<DDSpan>) o);
      }

      traces.sort(traceSorter);
      ListWriterAssert asserter = new ListWriterAssert(traces);
      spec.accept(asserter);

      if (!ignoreAdditionalTraces) {
        asserter.assertTracesAllVerified();

        if (writer.size() > traces.size()) {
          List<List<DDSpan>> extras = new ArrayList<>((Collection<List<DDSpan>>) writer);
          extras.removeAll(traces);

          StringBuilder message =
              new StringBuilder("ListWriter obtained ")
                  .append(extras.size())
                  .append(" additional traces while validating:");
          for (List<DDSpan> extra : extras) {
            message.append("\n").append(extra);
          }
          message.append('\n');
          throw new AssertionError(message.toString());
        }
      }
    } catch (PowerAssertionError e) {
      StackTraceElement stackLine = null;
      for (StackTraceElement element : e.getStackTrace()) {
        String className = element.getClassName();
        boolean skip =
            className.startsWith("org.codehaus.groovy.")
                || className.startsWith("datadog.trace.agent.test.")
                || className.startsWith("sun.reflect.")
                || className.startsWith("groovy.lang.")
                || className.startsWith("java.lang.");
        if (!skip) {
          stackLine = element;
          break;
        }
      }
      Condition condition =
          new Condition(
              null,
              String.valueOf(stackLine),
              TextPosition.create(stackLine == null ? 0 : stackLine.getLineNumber(), 0),
              e.getMessage(),
              null,
              e);
      throw new ConditionNotSatisfiedError(condition, e);
    } catch (InterruptedException | TimeoutException e) {
      throw new RuntimeException(e);
    }
  }

  public void sortSpansByStart() {
    this.traces =
        traces.stream()
            .map(
                it -> {
                  List<DDSpan> copy = new ArrayList<>(it);
                  copy.sort(Comparator.comparingLong(span -> span.getStartTimeNano()));
                  return copy;
                })
            .collect(Collectors.toList());
  }

  public List<DDSpan> trace(int index) {
    return Collections.unmodifiableList(traces.get(index));
  }

  public void trace(int expectedSize, Consumer<TraceAssert> spec) {
    trace(expectedSize, false, spec);
  }

  public void trace(int expectedSize, boolean sortByName, Consumer<TraceAssert> spec) {
    trace(expectedSize, sortByName ? TraceAssert.NAME_COMPARATOR : null, spec);
  }

  public void trace(int expectedSize, Comparator<DDSpan> sorter, Consumer<TraceAssert> spec) {
    int index = traceAssertCount.getAndIncrement();
    if (index >= size) {
      throw new ArrayIndexOutOfBoundsException(index);
    }
    if (traces.size() != size) {
      throw new ConcurrentModificationException("ListWriter modified during assertion");
    }
    assertedIndexes.add(index);
    assertTrace(trace(index), expectedSize, sorter, spec);
  }

  public void assertTracesAllVerified() {
    if (assertedIndexes.size() != size) {
      throw new AssertionError("Not all traces were verified.");
    }
  }

  private static class SortTracesByStart implements Comparator<List<DDSpan>> {
    @Override
    public int compare(List<DDSpan> o1, List<DDSpan> o2) {
      return Long.compare(traceStart(o1), traceStart(o2));
    }

    private long traceStart(List<DDSpan> trace) {
      if (trace.isEmpty()) throw new AssertionError("Trace empty");
      return trace.get(0).getLocalRootSpan().getStartTime();
    }
  }

  private static class SortTracesById implements Comparator<List<DDSpan>> {
    @Override
    public int compare(List<DDSpan> o1, List<DDSpan> o2) {
      return Long.compare(rootSpanId(o1), rootSpanId(o2));
    }

    private long rootSpanId(List<DDSpan> trace) {
      if (trace.isEmpty()) throw new AssertionError("Trace empty");
      return trace.get(0).getLocalRootSpan().getSpanId();
    }
  }

  private static class SortTracesByNames implements Comparator<List<DDSpan>> {
    @Override
    public int compare(List<DDSpan> o1, List<DDSpan> o2) {
      return rootSpanTrace(o1).compareTo(rootSpanTrace(o2));
    }

    private String rootSpanTrace(List<DDSpan> trace) {
      if (trace.isEmpty()) throw new AssertionError("Trace empty");
      DDSpan root = trace.get(0).getLocalRootSpan();
      return root.getServiceName() + "/" + root.getOperationName() + "/" + root.getResourceName();
    }
  }
}
