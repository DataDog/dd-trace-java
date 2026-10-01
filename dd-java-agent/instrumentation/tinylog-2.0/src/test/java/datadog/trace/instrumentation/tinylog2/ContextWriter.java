package datadog.trace.instrumentation.tinylog2;

import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.tinylog.core.LogEntry;
import org.tinylog.core.LogEntryValue;
import org.tinylog.writers.Writer;

/** Records the correlation context seen by tinylog writers. */
public class ContextWriter implements Writer {
  public static final List<Map<String, String>> CONTEXTS = new CopyOnWriteArrayList<>();
  private static final AtomicInteger INSTANCES = new AtomicInteger();

  private final String id = String.valueOf(INSTANCES.getAndIncrement());

  public ContextWriter(Map<String, String> properties) {}

  @Override
  public Collection<LogEntryValue> getRequiredLogEntryValues() {
    return EnumSet.of(LogEntryValue.MESSAGE, LogEntryValue.CONTEXT);
  }

  @Override
  public void write(LogEntry logEntry) {
    Map<String, String> context = logEntry.getContext();
    Map<String, String> captured = new HashMap<>();
    captured.put("writer", id);
    captured.put("thread", Thread.currentThread().getName());
    captured.put("dd.trace_id", context.get("dd.trace_id"));
    captured.put("dd.span_id", context.get("dd.span_id"));
    captured.put("custom", context.get("custom"));
    CONTEXTS.add(captured);
  }

  @Override
  public void flush() {}

  @Override
  public void close() {}
}
