package datadog.trace.ci.writer;

import datadog.trace.common.writer.DDAgentWriter;
import datadog.trace.core.DDSpan;
import java.util.List;

/**
 * This writer is specific for CI Visibility using Datadog Agent. It is based on the {@code
 * DDAgentWriter} class applying the following logic: - Only traces with span.type=test (local root
 * span) are sent. - All children spans contains the origin tag.
 */
public class CIAgentWriter implements CIWriter {

  private final DDAgentWriter agentWriter;

  CIAgentWriter(final DDAgentWriter agentWriter) {
    this.agentWriter = agentWriter;
  }

  @Override
  public void write(List<DDSpan> trace) {
    this.agentWriter.write(trace);
  }

  @Override
  public void start() {
    this.agentWriter.start();
  }

  @Override
  public boolean flush() {
    return this.agentWriter.flush();
  }

  @Override
  public void close() {
    this.agentWriter.close();
  }

  @Override
  public void incrementDropCounts(int spanCount) {
    this.agentWriter.incrementDropCounts(spanCount);
  }
}
