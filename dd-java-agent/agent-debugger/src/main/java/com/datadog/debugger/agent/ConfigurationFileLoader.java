package com.datadog.debugger.agent;

import static com.datadog.debugger.probe.ProbeDefinitionDeserializer.deserializeLogProbe;
import static com.datadog.debugger.probe.ProbeDefinitionDeserializer.deserializeMetricProbe;
import static com.datadog.debugger.probe.ProbeDefinitionDeserializer.deserializeSpanDecorationProbe;
import static com.datadog.debugger.probe.ProbeDefinitionDeserializer.deserializeSpanProbe;
import static com.datadog.debugger.probe.ProbeDefinitionDeserializer.deserializeTriggerProbe;

import com.datadog.debugger.probe.ProbeDefinition;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.JsonWriter;
import datadog.trace.util.SizeCheckedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
      JsonAdapter<List<ProbeDefinition>> adapter = new ProbeFileAdapter();
      List<ProbeDefinition> probeDefinitions =
          adapter.fromJson(
              JsonReader.of(Okio.buffer(Okio.source(new ByteArrayInputStream(configContent)))));
      return new Configuration(null, probeDefinitions);
    } catch (IOException ex) {
      LOGGER.error("Unable to load config file {}: {}", probeFilePath, ex);
      return null;
    }
  }

  private static class ProbeFileAdapter extends JsonAdapter<List<ProbeDefinition>> {

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
                probeDefinitions.add(deserializeLogProbe(reader));
                break;
              case "METRIC_PROBE":
                probeDefinitions.add(deserializeMetricProbe(reader));
                break;
              case "SPAN_PROBE":
                probeDefinitions.add(deserializeSpanProbe(reader));
                break;
              case "SPAN_DECORATION_PROBE":
                probeDefinitions.add(deserializeSpanDecorationProbe(reader));
                break;
              case "TRIGGER_PROBE":
                probeDefinitions.add(deserializeTriggerProbe(reader));
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
