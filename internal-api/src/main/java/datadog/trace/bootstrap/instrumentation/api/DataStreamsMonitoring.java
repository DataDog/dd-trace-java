package datadog.trace.bootstrap.instrumentation.api;

import java.util.List;
import java.util.function.Consumer;

public interface DataStreamsMonitoring extends Consumer<StatsPoint>, AutoCloseable {
  void start();

  PathwayContext newPathwayContext();

  <C> PathwayContext extractBinaryPathwayContext(
      C carrier, AgentPropagation.BinaryContextVisitor<C> getter);

  <C> PathwayContext extractPathwayContext(C carrier, AgentPropagation.ContextVisitor<C> getter);

  void trackBacklog(List<String> sortedTags, long value);

  @Override
  void close();

  void clear();
}
