package datadog.smoketest.opentracing;

import datadog.opentracing.DDTracer;
import datadog.trace.common.writer.ListWriter;

public class CorrectSetupWithAgentAndWriterApplication {
  public static void main(final String[] args) {
    try {
      final ListWriter writer = new ListWriter();
      DDTracer.builder().writer(writer).build();
    } catch (final IllegalStateException e) {
      throw new RuntimeException("build() threw exception");
    }
  }
}
