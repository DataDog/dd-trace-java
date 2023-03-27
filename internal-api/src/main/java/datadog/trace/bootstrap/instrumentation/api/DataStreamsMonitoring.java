package datadog.trace.bootstrap.instrumentation.api;

import java.util.LinkedHashMap;
import java.util.function.Consumer;

public interface DataStreamsMonitoring extends Consumer<StatsPoint>, AutoCloseable {
  void start();

  PathwayContext newPathwayContext();

  <C> PathwayContext extractBinaryPathwayContext(
      C carrier, AgentPropagation.BinaryContextVisitor<C> getter);

  <C> PathwayContext extractPathwayContext(C carrier, AgentPropagation.ContextVisitor<C> getter);

  void trackBacklog(LinkedHashMap<String, String> sortedTags, long value);

  @Override
  void close();

  void clear();
}
