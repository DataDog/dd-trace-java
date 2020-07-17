package datadog.trace.common.writer;

import dagger.Module;
import dagger.Provides;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.WriterConstants;
import datadog.trace.common.writer.ddagent.DDAgentWriterModule;
import javax.inject.Provider;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Module(includes = DDAgentWriterModule.class)
public class WriterModule {
  private final Writer writer;

  public WriterModule() {
    this(null);
  }

  public WriterModule(final Writer writer) {
    this.writer = writer;
  }

  @Singleton
  @Provides
  Writer writer(final Config config, final Provider<DDAgentWriter> writerProvider) {
    if (writer != null) {
      writer.start();
      return writer;
    }

    final Writer writer;

    final String configuredType = config.getWriterType();

    if (WriterConstants.LOGGING_WRITER_TYPE.equals(configuredType)) {
      writer = new LoggingWriter();
    } else {
      if (!WriterConstants.DD_AGENT_WRITER_TYPE.equals(configuredType)) {
        log.warn(
            "Writer type not configured correctly: Type {} not recognized. Defaulting to DDAgentWriter.",
            configuredType);
      }
      writer = writerProvider.get();
    }

    writer.start();
    return writer;
  }
}
