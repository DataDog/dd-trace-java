package datadog.trace.core.monitor;

import static datadog.trace.common.writer.RemoteApi.Response.failed;
import static datadog.trace.common.writer.RemoteApi.Response.success;
import static datadog.trace.core.monitor.ExporterId.Signal.LOG;
import static datadog.trace.core.monitor.ExporterId.Signal.METRIC_DATA_POINT;
import static datadog.trace.core.monitor.ExporterId.Signal.SPAN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import datadog.trace.api.config.OtlpConfig.Protocol;
import datadog.trace.common.writer.RemoteApi;
import java.io.IOException;
import okhttp3.HttpUrl;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.tabletest.junit.TableTest;

class ExportRecorderTest {
  private static final HttpUrl DESTINATION = HttpUrl.get("http://collector:4318/v1/traces");

  @Test
  void reportsStartThenOneEndWithTheResponse() {
    HealthMetrics sink = mock(HealthMetrics.class);
    ExporterId id = ExporterId.otlp(SPAN, Protocol.HTTP_PROTOBUF, DESTINATION);
    RemoteApi.Response response = success(200);

    ExportRecorder.Export export = ExportRecorder.create(sink, id).start(3);
    export.finish(response);
    export.finish(failed(500)); // a finished export ignores later outcomes
    export.failure(new IOException());

    InOrder order = inOrder(sink);
    order.verify(sink).onExportStart(id, 3);
    order.verify(sink).onExportEnd(eq(id), eq(3L), anyLong(), same(response), isNull());
    verifyNoMoreInteractions(sink);
  }

  @Test
  void reportsTheExceptionThrownBySend() {
    HealthMetrics sink = mock(HealthMetrics.class);
    ExporterId id = ExporterId.otlp(LOG, Protocol.GRPC, null);
    IOException error = new IOException();

    ExportRecorder.create(sink, id).start(2).failure(error);

    verify(sink).onExportEnd(eq(id), eq(2L), anyLong(), isNull(), same(error));
  }

  @Test
  void noOpSinkGetsTheNoOpRecorder() {
    ExporterId id = ExporterId.otlp(SPAN, Protocol.HTTP_PROTOBUF, DESTINATION);

    ExportRecorder recorder = ExportRecorder.create(HealthMetrics.NO_OP, id);

    assertSame(ExportRecorder.NO_OP, recorder);
    recorder.start(3).finish(success(200));
    recorder.start(3).failure(new IOException());
  }

  @Test
  void realSinkRequiresAnExporter() {
    assertThrows(
        NullPointerException.class, () -> ExportRecorder.create(mock(HealthMetrics.class), null));
  }

  @TableTest({
    "protocol      | signal            | type                          ",
    "GRPC          | METRIC_DATA_POINT | otlp_grpc_metric_exporter     ",
    "HTTP_PROTOBUF | METRIC_DATA_POINT | otlp_http_metric_exporter     ",
    "HTTP_JSON     | METRIC_DATA_POINT | otlp_http_json_metric_exporter",
    "GRPC          | SPAN              | otlp_grpc_span_exporter       ",
    "HTTP_JSON     | LOG               | otlp_http_json_log_exporter   "
  })
  void namesOtlpExporterTypesByProtocolAndSignal(
      Protocol protocol, ExporterId.Signal signal, String type) {
    assertEquals(type, ExporterId.otlpExporterType(protocol, signal));
  }

  @Test
  void otlpExporterIdsCarryTheirIdentity() {
    ExporterId grpc = ExporterId.otlp(METRIC_DATA_POINT, Protocol.GRPC, DESTINATION);
    ExporterId http = ExporterId.otlp(LOG, Protocol.HTTP_PROTOBUF, null);

    assertSame(METRIC_DATA_POINT, grpc.signal());
    assertEquals("otlp_grpc_metric_exporter", grpc.component().type());
    assertTrue(grpc.component().name().startsWith("otlp_grpc_metric_exporter/"));
    assertEquals(grpc.component().name(), grpc.toString());
    assertEquals("collector", grpc.serverAddress());
    assertEquals(4318, grpc.serverPort());
    assertTrue(grpc.grpc());
    assertFalse(http.grpc());
    assertNull(http.serverAddress());
    assertEquals(0, http.serverPort());
  }

  @Test
  void namesComponentsPerType() {
    ComponentId first = ComponentId.next("export_recorder_test_naming");
    ComponentId second = ComponentId.next("export_recorder_test_naming");

    assertEquals("export_recorder_test_naming", first.type());
    assertEquals("export_recorder_test_naming/0", first.name());
    assertEquals("export_recorder_test_naming/1", second.name());
    assertEquals("export_recorder_test_naming/1", second.toString());
    assertEquals(
        "export_recorder_test_other/0", ComponentId.next("export_recorder_test_other").name());
  }
}
