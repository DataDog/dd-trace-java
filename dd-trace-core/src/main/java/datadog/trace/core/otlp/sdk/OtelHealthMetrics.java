package datadog.trace.core.otlp.sdk;

import static datadog.trace.bootstrap.otel.api.common.AttributeKey.longKey;
import static datadog.trace.bootstrap.otel.api.common.AttributeKey.stringKey;
import static datadog.trace.bootstrap.otel.metrics.OtelInstrumentType.COUNTER;
import static datadog.trace.bootstrap.otel.metrics.OtelInstrumentType.HISTOGRAM;
import static datadog.trace.bootstrap.otel.metrics.OtelInstrumentType.UP_DOWN_COUNTER;
import static java.util.Collections.emptyList;

import datadog.trace.bootstrap.otel.api.common.AttributeKey;
import datadog.trace.bootstrap.otel.api.common.Attributes;
import datadog.trace.bootstrap.otel.api.common.AttributesBuilder;
import datadog.trace.bootstrap.otel.metrics.OtelInstrumentBuilder;
import datadog.trace.bootstrap.otel.metrics.OtelInstrumentType;
import datadog.trace.bootstrap.otel.metrics.data.OtelMetricStorage;
import datadog.trace.common.writer.RemoteApi;
import datadog.trace.core.DDSpan;
import datadog.trace.core.DDSpanContext;
import datadog.trace.core.monitor.HealthMetrics;
import datadog.trace.core.monitor.OtelComponentId;
import datadog.trace.core.monitor.OtelExporterId;
import datadog.trace.core.monitor.OtelExporterId.Signal;
import datadog.trace.core.otlp.common.OtlpGrpcStatusException;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;
import javax.annotation.Nullable;

/**
 * {@link HealthMetrics} that records the OTel SDK self-monitoring metrics:
 *
 * <ul>
 *   <li>{@code otel.sdk.span.started}, split by parent origin, and {@code otel.sdk.span.live},
 *       which semantic conventions do not split by parent origin. The hot path only touches {@link
 *       LongAdder}s; observable instruments report the adders at collection.
 *   <li>{@code otel.sdk.exporter.<signal>.inflight}, {@code .exported} and {@code
 *       otel.sdk.exporter.operation.duration}, modeled on the OTel Java SDK's {@code
 *       SemConvExporterMetrics}, one series per {@link OtelExporterId}.
 *   <li>{@code otel.sdk.metric_reader.collection.duration}, one series per reader {@link
 *       OtelComponentId}.
 * </ul>
 *
 * Every other callback stays a no-op.
 */
final class OtelHealthMetrics extends HealthMetrics {
  static final AttributeKey<String> PARENT_ORIGIN = stringKey("otel.span.parent.origin");
  static final AttributeKey<String> COMPONENT_TYPE = stringKey("otel.component.type");
  static final AttributeKey<String> COMPONENT_NAME = stringKey("otel.component.name");
  static final AttributeKey<String> ERROR_TYPE = stringKey("error.type");
  static final AttributeKey<String> SERVER_ADDRESS = stringKey("server.address");
  static final AttributeKey<Long> SERVER_PORT = longKey("server.port");
  static final AttributeKey<Long> HTTP_STATUS_CODE = longKey("http.response.status_code");
  static final AttributeKey<String> RPC_STATUS_CODE = stringKey("rpc.status_code");

  /** The gRPC status name of a successful call. */
  static final String GRPC_OK = OtlpGrpcStatusException.statusName(0);

  /**
   * Indexed by {@link DDSpanContext#getParentOrigin()}, as is the {@code started} adder array;
   * {@code OtelHealthMetricsTest} checks every {@code PARENT_ORIGIN_*} constant maps here.
   */
  static final Attributes[] ORIGINS = {
    Attributes.of(PARENT_ORIGIN, "none"),
    Attributes.of(PARENT_ORIGIN, "local"),
    Attributes.of(PARENT_ORIGIN, "remote")
  };

  private static final Attributes[] NO_ATTRIBUTES = {Attributes.empty()};

  /**
   * One per JVM: the observables read these adders, and a second set would double-report. Declared
   * after the attribute constants, so they exist before the constructor registers the observables.
   */
  static final OtelHealthMetrics INSTANCE = new OtelHealthMetrics();

  final LongAdder[] started = {new LongAdder(), new LongAdder(), new LongAdder()};
  private final LongAdder live = new LongAdder();

  private final Map<Signal, ExportInstruments> exports = new EnumMap<>(Signal.class);
  private final OtelMetricStorage exportDuration;
  private final OtelMetricStorage collectionDuration;

  private OtelHealthMetrics() {
    observe("otel.sdk.span.started", COUNTER, "The number of created spans.", started, ORIGINS);
    observe(
        "otel.sdk.span.live",
        UP_DOWN_COUNTER,
        "The number of created spans with recording=true for which the end operation has not"
            + " been called yet.",
        new LongAdder[] {live},
        NO_ATTRIBUTES);
    exports.put(Signal.SPAN, new ExportInstruments("span", "{span}", "spans"));
    exports.put(Signal.LOG, new ExportInstruments("log", "{log_record}", "log records"));
    exports.put(
        Signal.METRIC_DATA_POINT,
        new ExportInstruments("metric_data_point", "{data_point}", "metric data points"));
    exportDuration =
        OtelSdkRegistry.storage(
            OtelInstrumentBuilder.ofDoubles("otel.sdk.exporter.operation.duration", HISTOGRAM),
            false,
            "The duration of exporting a batch of telemetry records.",
            "s",
            // no bucket boundaries: a single bucket with count, sum, min and max
            descriptor -> OtelMetricStorage.newHistogramStorage(descriptor, emptyList()));
    collectionDuration =
        OtelSdkRegistry.storage(
            OtelInstrumentBuilder.ofDoubles(
                "otel.sdk.metric_reader.collection.duration", HISTOGRAM),
            false,
            "The duration of the collect operation of the metric reader.",
            "s",
            descriptor -> OtelMetricStorage.newHistogramStorage(descriptor, emptyList()));
  }

  @Override
  public void onCreateSpan(DDSpan span) {
    started[span.spanContext().getParentOrigin()].increment();
    live.increment();
  }

  /** Called when the span ends, so a phased-finish span leaves {@code live} before publish. */
  @Override
  public void onEndSpan() {
    live.decrement();
  }

  @Override
  public void onExportStart(OtelExporterId exporter, long itemCount) {
    exports.get(exporter.signal()).inflight.recordLong(itemCount, baseAttributes(exporter));
  }

  /**
   * Records the finished export. {@code error.type} is the gRPC status name for a gRPC status
   * failure, {@code timeout} or the exception class name for other exceptions (see {@link
   * ErrorTypes}), the HTTP status code for a non-success response, and {@code _OTHER} otherwise.
   */
  @Override
  public void onExportEnd(
      OtelExporterId exporter,
      long itemCount,
      long durationNanos,
      @Nullable RemoteApi.Response response,
      @Nullable Throwable error) {
    ExportInstruments instruments = exports.get(exporter.signal());
    Attributes base = baseAttributes(exporter);
    instruments.inflight.recordLong(-itemCount, base);

    if (error == null && response != null && !response.success()) {
      error = response.exception().orElse(null);
    }
    String errorType = null;
    String grpcStatus = null;
    int httpStatus = 0;
    if (error instanceof OtlpGrpcStatusException) {
      grpcStatus = ((OtlpGrpcStatusException) error).statusName();
      errorType = grpcStatus;
    } else if (error != null) {
      errorType = ErrorTypes.fromException(error);
    } else if (response == null) {
      errorType = ErrorTypes.OTHER;
    } else {
      httpStatus = response.status().orElse(0);
      if (response.success()) {
        if (exporter.grpc()) {
          grpcStatus = GRPC_OK;
        }
      } else {
        errorType = httpStatus > 0 ? Integer.toString(httpStatus) : ErrorTypes.OTHER;
      }
    }

    Attributes outcome =
        errorType == null ? base : base.toBuilder().put(ERROR_TYPE, errorType).build();
    instruments.exported.recordLong(itemCount, outcome);
    if (exporter.grpc() && grpcStatus != null) {
      outcome = outcome.toBuilder().put(RPC_STATUS_CODE, grpcStatus).build();
    } else if (!exporter.grpc() && httpStatus > 0) {
      outcome = outcome.toBuilder().put(HTTP_STATUS_CODE, httpStatus).build();
    }
    exportDuration.recordDouble(durationNanos / 1e9, outcome);
  }

  /**
   * Records the collection, with {@code error.type} (see {@link ErrorTypes}) when it threw.
   * Failures inside observable callbacks are caught and logged by the callbacks, so they don't set
   * {@code error.type}.
   */
  @Override
  public void onMetricCollection(
      OtelComponentId reader, long durationNanos, @Nullable Throwable error) {
    AttributesBuilder attributes = componentAttributes(reader);
    if (error != null) {
      attributes.put(ERROR_TYPE, ErrorTypes.fromException(error));
    }
    collectionDuration.recordDouble(durationNanos / 1e9, attributes.build());
  }

  /**
   * {@code otel.component.*}, plus {@code server.*} when the destination is known. Built per call
   * rather than cached: exports are infrequent, and the sink keeps no per-exporter state.
   */
  private static Attributes baseAttributes(OtelExporterId exporter) {
    AttributesBuilder builder = componentAttributes(exporter.component());
    String serverAddress = exporter.serverAddress();
    if (serverAddress != null) {
      builder.put(SERVER_ADDRESS, serverAddress);
      builder.put(SERVER_PORT, exporter.serverPort());
    }
    return builder.build();
  }

  private static AttributesBuilder componentAttributes(OtelComponentId component) {
    return Attributes.builder()
        .put(COMPONENT_TYPE, component.type())
        .put(COMPONENT_NAME, component.name());
  }

  /**
   * Records every series on each collection, 0 included: a skipped observable series is dropped,
   * and when it returns, a delta export would report its whole cumulative count.
   */
  private static void observe(
      String name,
      OtelInstrumentType type,
      String description,
      LongAdder[] adders,
      Attributes[] attributes) {
    OtelMetricStorage storage =
        OtelSdkRegistry.storage(
            OtelInstrumentBuilder.ofLongs(name, type),
            true,
            description,
            "{span}",
            OtelMetricStorage::newLongDeltaStorage);
    OtelSdkRegistry.observe(() -> {
      for (int i = 0; i < adders.length; i++) {
        storage.recordLong(adders[i].sum(), attributes[i]);
      }
    });
  }

  /** The {@code inflight} and {@code exported} instruments of one signal. */
  private static final class ExportInstruments {
    final OtelMetricStorage inflight;
    final OtelMetricStorage exported;

    ExportInstruments(String signal, String unit, String items) {
      String prefix = "otel.sdk.exporter." + signal;
      inflight =
          OtelSdkRegistry.storage(
              OtelInstrumentBuilder.ofLongs(prefix + ".inflight", UP_DOWN_COUNTER),
              false,
              "The number of "
                  + items
                  + " which were passed to the exporter, but that have not been exported yet"
                  + " (neither successful, nor failed).",
              unit,
              OtelMetricStorage::newLongSumStorage);
      exported =
          OtelSdkRegistry.storage(
              OtelInstrumentBuilder.ofLongs(prefix + ".exported", COUNTER),
              false,
              "The number of "
                  + items
                  + " for which the export has finished, either successful"
                  + " or failed.",
              unit,
              OtelMetricStorage::newLongSumStorage);
    }
  }
}
