package com.datadog.debugger.agent;

import com.datadog.debugger.probe.LogProbe;
import com.datadog.debugger.probe.MetricProbe;
import com.datadog.debugger.probe.ProbeDefinition;
import com.datadog.debugger.probe.SpanDecorationProbe;
import com.datadog.debugger.probe.SpanProbe;
import com.datadog.debugger.probe.TriggerProbe;
import com.datadog.debugger.util.MoshiHelper;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.JsonWriter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import datadog.trace.util.SizeCheckedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import okio.Okio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ConfigurationFileLoader {
  private static final Logger LOGGER = LoggerFactory.getLogger(ConfigurationFileLoader.class);

  public static Configuration from(Path probeFilePath, long maxPayloadSize) {
    LOGGER.debug("try to load from file...");
    try (InputStream inputStream =
        new SizeCheckedInputStream(new FileInputStream(probeFilePath.toFile()), maxPayloadSize)) {
      byte[] buffer = new byte[4096];
      ByteArrayOutputStream outputStream = new ByteArrayOutputStream(4096);
      int bytesRead;
      do {
        bytesRead = inputStream.read(buffer);
        if (bytesRead > -1) {
          outputStream.write(buffer, 0, bytesRead);
        }
      } while (bytesRead > -1);
      byte[] configContent = outputStream.toByteArray();
      Moshi moshi = MoshiHelper.createMoshiConfigBuilder().add(new ProbeFileFactory()).build();
      ParameterizedType type = Types.newParameterizedType(List.class, ProbeDefinition.class);
      JsonAdapter<List<ProbeDefinition>> adapter = moshi.adapter(type);
      List<ProbeDefinition> probeDefinitions =
          adapter.fromJson(
              JsonReader.of(Okio.buffer(Okio.source(new ByteArrayInputStream(configContent)))));
      return new Configuration(null, probeDefinitions);
    } catch (IOException ex) {
      LOGGER.error("Unable to load config file {}: {}", probeFilePath, ex);
      return null;
    }
  }

  private static class ProbeFileFactory implements JsonAdapter.Factory {
    @Override
    public JsonAdapter<?> create(Type type, Set<? extends Annotation> annotations, Moshi moshi) {
      if (Types.equals(type, Types.newParameterizedType(List.class, ProbeDefinition.class))) {
        return new ProbeFileAdapter(
            moshi.adapter(LogProbe.class),
            moshi.adapter(MetricProbe.class),
            moshi.adapter(SpanProbe.class),
            moshi.adapter(SpanDecorationProbe.class),
            moshi.adapter(TriggerProbe.class));
      }
      return null;
    }
  }

  private static class ProbeFileAdapter extends JsonAdapter<List<ProbeDefinition>> {
    private final JsonAdapter<LogProbe> logProbeAdapter;
    private final JsonAdapter<MetricProbe> metricProbeAdapter;
    private final JsonAdapter<SpanProbe> spanProbeAdapter;
    private final JsonAdapter<SpanDecorationProbe> spanDecorationProbeAdapter;
    private final JsonAdapter<TriggerProbe> triggerProbeAdapter;

    public ProbeFileAdapter(
        JsonAdapter<LogProbe> logProbeAdapter,
        JsonAdapter<MetricProbe> metricProbeAdapter,
        JsonAdapter<SpanProbe> spanProbeAdapter,
        JsonAdapter<SpanDecorationProbe> spanDecorationProbeAdapter,
        JsonAdapter<TriggerProbe> triggerProbeAdapter) {
      this.logProbeAdapter = logProbeAdapter;
      this.metricProbeAdapter = metricProbeAdapter;
      this.spanProbeAdapter = spanProbeAdapter;
      this.spanDecorationProbeAdapter = spanDecorationProbeAdapter;
      this.triggerProbeAdapter = triggerProbeAdapter;
    }

    @Override
    public List<ProbeDefinition> fromJson(JsonReader reader) throws IOException {
      List<ProbeDefinition> probeDefinitions = new ArrayList<>();
      reader.beginArray();
      while (reader.hasNext()) {
        if (reader.peek() == JsonReader.Token.END_ARRAY) {
          reader.endArray();
          break;
        }
        JsonReader jsonPeekReader = reader.peekJson();
        jsonPeekReader.beginObject();
        while (jsonPeekReader.hasNext()) {
          if (jsonPeekReader.selectName(JsonReader.Options.of("type")) == 0) {
            String type = jsonPeekReader.nextString();
            switch (type) {
              case "LOG_PROBE":
                probeDefinitions.add(logProbeAdapter.fromJson(reader));
                break;
              case "METRIC_PROBE":
                probeDefinitions.add(metricProbeAdapter.fromJson(reader));
                break;
              case "SPAN_PROBE":
                probeDefinitions.add(spanProbeAdapter.fromJson(reader));
                break;
              case "SPAN_DECORATION_PROBE":
                probeDefinitions.add(spanDecorationProbeAdapter.fromJson(reader));
                break;
              case "TRIGGER_PROBE":
                probeDefinitions.add(triggerProbeAdapter.fromJson(reader));
                break;
              default:
                throw new RuntimeException("Unknown type: " + type);
            }
            break;
          } else {
            jsonPeekReader.skipName();
            jsonPeekReader.skipValue();
          }
        }
      }
      return probeDefinitions;
    }

    @Override
    public void toJson(JsonWriter writer, List<ProbeDefinition> value) throws IOException {
      // Implement the logic to write the list of ProbeDefinition to JSON
    }
  }
}
