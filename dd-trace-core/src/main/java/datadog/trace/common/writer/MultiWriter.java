package datadog.trace.common.writer;

import static datadog.trace.bootstrap.instrumentation.api.WriterConstants.MULTI_WRITER_TYPE;

import datadog.trace.api.Config;
import datadog.trace.api.StatsDClient;
import datadog.trace.common.sampling.Sampler;
import datadog.trace.core.DDSpan;
import datadog.trace.core.monitor.Monitoring;
import java.util.List;
import java.util.regex.Pattern;

public class MultiWriter implements Writer {
  private static final Pattern MW_PATTERN =
      Pattern.compile(MULTI_WRITER_TYPE + ":", Pattern.LITERAL);
  private static final Pattern COMMA_PATTERN = Pattern.compile(",", Pattern.LITERAL);

  private final Writer[] writers;

  public MultiWriter(
      Config config,
      Sampler sampler,
      StatsDClient statsDClient,
      Monitoring monitoring,
      String type) {
    String mwConfig = MW_PATTERN.matcher(type).replaceAll("");
    String[] writerConfigs = COMMA_PATTERN.split(mwConfig);
    this.writers = new Writer[writerConfigs.length];
    int i = 0;

    for (String writerConfig : writerConfigs) {
      writers[i] =
          WriterFactory.createWriter(config, sampler, statsDClient, monitoring, writerConfig);
      i++;
    }
  }

  public MultiWriter(Writer[] writers) {
    this.writers = writers.clone();
  }

  @Override
  public void start() {
    for (Writer writer : writers) {
      if (writer != null) {
        writer.start();
      }
    }
  }

  @Override
  public void write(List<DDSpan> trace) {
    for (Writer writer : writers) {
      if (writer != null) {
        writer.write(trace);
      }
    }
  }

  @Override
  public boolean flush() {
    boolean flush = true;
    for (Writer writer : writers) {
      if (writer != null) {
        flush &= writer.flush();
      }
    }

    return flush;
  }

  @Override
  public void close() {
    for (Writer writer : writers) {
      if (writer != null) {
        writer.close();
      }
    }
  }

  @Override
  public void incrementDropCounts(int spanCount) {
    for (Writer writer : writers) {
      if (writer != null) {
        writer.incrementDropCounts(spanCount);
      }
    }
  }
}
