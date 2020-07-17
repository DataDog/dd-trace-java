package datadog.trace.common.writer.ddagent;

import static datadog.trace.common.writer.DDAgentWriter.DISRUPTOR_BUFFER_SIZE;

import dagger.Module;
import dagger.Provides;
import java.util.concurrent.TimeUnit;
import javax.inject.Named;
import javax.inject.Singleton;

@Module
public class DDAgentWriterModule {

  @Singleton
  @Provides
  TraceProcessingDisruptor traceProcessingDisruptor(
      final Monitor monitor,
      final DDAgentApi api,
      final @Named("disruptorBufferSize") int disruptorBufferSize,
      final @Named("flushFrequencySeconds") int flushFrequencySeconds) {
    return new TraceProcessingDisruptor(
        disruptorBufferSize,
        monitor,
        api,
        flushFrequencySeconds,
        TimeUnit.SECONDS,
        flushFrequencySeconds > 0);
  }

  @Provides
  @Named("flushFrequencySeconds")
  int flushFrequencySeconds() {
    return 1;
  }

  @Provides
  @Named("disruptorBufferSize")
  int disruptorBufferSize() {
    return DISRUPTOR_BUFFER_SIZE;
  }
}
