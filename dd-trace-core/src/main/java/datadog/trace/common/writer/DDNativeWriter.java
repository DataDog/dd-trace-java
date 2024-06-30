package datadog.trace.common.writer;

import static datadog.trace.api.ConfigDefaults.DEFAULT_AGENT_HOST;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_AGENT_PORT;
import static datadog.trace.common.writer.DDAgentWriter.BUFFER_SIZE;
import static datadog.trace.common.writer.ddagent.DDAgentApi.RESPONSE_ADAPTER;

import datadog.communication.ddagent.DroppingPolicy;
import datadog.communication.monitor.Monitoring;
import datadog.data_pipeline.TraceExporter;
import datadog.trace.common.sampling.Sampler;
import datadog.trace.common.sampling.SingleSpanSampler;
import datadog.trace.common.writer.ddagent.Prioritization;
import datadog.trace.common.writer.ddagent.TraceMapper;
import datadog.trace.common.writer.ddagent.TraceMapperV0_4;
import datadog.trace.core.DDTraceCoreInfo;
import datadog.trace.core.monitor.HealthMetrics;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DDNativeWriter extends RemoteWriter {

  public static DDNativeWriterBuilder builder() {
    return new DDNativeWriterBuilder();
  }

  public static final class DDNativeWriterBuilder {
    private static Logger log = LoggerFactory.getLogger(DDNativeWriterBuilder.class);

    private String agentHost = DEFAULT_AGENT_HOST;
    private int traceAgentPort = DEFAULT_TRACE_AGENT_PORT;
    private int traceBufferSize = BUFFER_SIZE;
    private HealthMetrics healthMetrics = HealthMetrics.NO_OP;
    private Sampler sampler;
    private boolean proxy = true;
    private int flushTimeoutMillis = 1000;
    private Prioritization prioritization = Prioritization.FAST_LANE;
    private DroppingPolicy droppingPolicy = DroppingPolicy.DISABLED;
    private SingleSpanSampler singleSpanSampler;
    private Monitoring monitoring = Monitoring.DISABLED;

    public DDNativeWriterBuilder agentHost(String agentHost) {
      this.agentHost = agentHost;
      return this;
    }

    public DDNativeWriterBuilder traceAgentPort(int traceAgentPort) {
      this.traceAgentPort = traceAgentPort;
      return this;
    }

    public DDNativeWriterBuilder traceBufferSize(int traceBufferSize) {
      this.traceBufferSize = traceBufferSize;
      return this;
    }

    public DDNativeWriterBuilder healthMetrics(HealthMetrics healthMetrics) {
      this.healthMetrics = healthMetrics;
      return this;
    }

    public DDNativeWriterBuilder sampler(Sampler sampler) {
      this.sampler = sampler;
      return this;
    }

    public DDNativeWriterBuilder proxy(boolean proxy) {
      this.proxy = proxy;
      return this;
    }

    public DDNativeWriterBuilder flushTimeoutMillis(int flushTimeoutMillis) {
      this.flushTimeoutMillis = flushTimeoutMillis;
      return this;
    }

    public DDNativeWriterBuilder prioritization(Prioritization prioritization) {
      this.prioritization = prioritization;
      return this;
    }

    public DDNativeWriterBuilder droppingPolicy(DroppingPolicy droppingPolicy) {
      this.droppingPolicy = droppingPolicy;
      return this;
    }

    public DDNativeWriterBuilder singleSpanSampler(SingleSpanSampler singleSpanSampler) {
      this.singleSpanSampler = singleSpanSampler;
      return this;
    }

    public DDNativeWriterBuilder monitoring(Monitoring monitoring) {
      this.monitoring = monitoring;
      return this;
    }

    public DDNativeWriter build() {
      log.debug("Building DDNativeWriter");
      DDNativeMapperDiscovery mapperDiscovery = new DDNativeMapperDiscovery();
      int endBufferSize = mapperDiscovery.getMapper().messageBufferSize();
      log.debug("DDNativeMapperDiscovery built");
      DDNativeApi api =
          new DDNativeApi(
              endBufferSize,
              sampler,
              agentHost,
              traceAgentPort,
              DDTraceCoreInfo.VERSION,
              "jvm",
              DDTraceCoreInfo.JAVA_VERSION,
              DDTraceCoreInfo.JAVA_VM_NAME,
              proxy);
      log.debug("DDNativeApi built");
      PayloadDispatcher dispatcher =
          new PayloadDispatcherImpl(mapperDiscovery, api, healthMetrics, monitoring);
      log.debug("PayloadDispatcher built");
      TraceProcessingWorker worker =
          new TraceProcessingWorker(
              traceBufferSize,
              healthMetrics,
              dispatcher,
              droppingPolicy,
              prioritization,
              flushTimeoutMillis,
              TimeUnit.MILLISECONDS,
              singleSpanSampler);
      log.debug("TraceProcessingWorker built");
      return new DDNativeWriter(
          worker, dispatcher, healthMetrics, flushTimeoutMillis, TimeUnit.MILLISECONDS, false);
    }
  }

  private DDNativeWriter(
      final TraceProcessingWorker traceProcessingWorker,
      final PayloadDispatcher dispatcher,
      final HealthMetrics healthMetrics,
      final int flushTimeout,
      final TimeUnit flushTimeoutUnit,
      final boolean alwaysFlush) {
    super(
        traceProcessingWorker,
        dispatcher,
        healthMetrics,
        flushTimeout,
        flushTimeoutUnit,
        alwaysFlush);
  }

  private static final class DDNativeApi extends RemoteApi {
    private static final Logger log = LoggerFactory.getLogger(DDNativeApi.class);

    private final TraceExporter exporter;
    private final ByteBuffer buffer;
    private final WritableByteChannel channel;
    private final RemoteResponseListener listener;

    private DDNativeApi(
        int bufferSize,
        Sampler sampler,
        String host,
        int port,
        String tracerVersion,
        String language,
        String languageVersion,
        String interpreter,
        boolean proxy) {
      super(false);
      try {
        log.debug("Initializing DDNativeApi");
        TraceExporter.initialize();
        log.debug("TraceExporter initialized");
        if (sampler instanceof RemoteResponseListener) {
          listener = (RemoteResponseListener) sampler;
        } else {
          listener = null;
        }
        // TODO size up the buffer to a 1KB increment
        buffer = ByteBuffer.allocateDirect(bufferSize);
        channel = new ByteBufferWritableByteChannel(buffer);
        exporter =
            TraceExporter.builder()
                .withHost(host)
                .withPort(port)
                .withTracerVersion(tracerVersion)
                .withLanguage(language)
                .withLanguageVersion(languageVersion)
                .withInterpreter(interpreter)
                .withProxy(proxy)
                .build();
        log.debug("TraceExporter built");
      } catch (final Exception e) {
        log.debug("Failed to initialize DDNativeApi", e);
        throw new ExceptionInInitializerError(e);
      }
    }

    @Override
    protected Response sendSerializedTraces(Payload payload) {
      int count = payload.traceCount();
      int size = payload.sizeInBytes();
      log.debug("Sending {} traces ({} bytes)", count, size);
      try {
        payload.writeTo(channel);
        buffer.flip();
        String response = "";
        try {
          response = exporter.sendTraces(buffer, size, count);
          log.debug("Received response: {}", response);
          countAndLogSuccessfulSend(count, size);
          if (!"".equals(response) && !"OK".equalsIgnoreCase(response)) {
            final Map<String, Map<String, Number>> parsedResponse =
                RESPONSE_ADAPTER.fromJson(response);
            if (listener != null) {
              // TODO faking the endpoint
              listener.onResponse("http://only.used.for.debug/", parsedResponse);
            }
          }
          // TODO faking the response code
          return Response.success(200, response);
        } catch (final IOException e) {
          log.debug("Failed to parse DD agent response: {}", response, e);
          // TODO faking the response code
          return Response.success(200, e);
        }
      } catch (final Exception e) {
        countAndLogFailedSend(count, size, null, e);
        return Response.failed(e);
      } finally {
        buffer.clear();
      }
    }

    @Override
    protected Logger getLogger() {
      return log;
    }
  }

  private static final class ByteBufferWritableByteChannel implements WritableByteChannel {
    private static final Logger log = LoggerFactory.getLogger(ByteBufferWritableByteChannel.class);
    private final ByteBuffer buffer;

    private ByteBufferWritableByteChannel(ByteBuffer buffer) {
      this.buffer = buffer;
    }

    @Override
    public int write(ByteBuffer src) {
      log.debug("Writing {} bytes", src.remaining());
      int remaining = src.remaining();
      buffer.put(src);
      return remaining;
    }

    @Override
    public boolean isOpen() {
      return true;
    }

    @Override
    public void close() {}
  }

  private static final class DDNativeMapperDiscovery implements RemoteMapperDiscovery {
    private static final Logger log = LoggerFactory.getLogger(DDNativeMapperDiscovery.class);
    private final TraceMapper traceMapper = new TraceMapperV0_4();

    @Override
    public void discover() {}

    @Override
    public RemoteMapper getMapper() {
      log.debug("Returning {}", traceMapper);
      return traceMapper;
    }
  }
}
