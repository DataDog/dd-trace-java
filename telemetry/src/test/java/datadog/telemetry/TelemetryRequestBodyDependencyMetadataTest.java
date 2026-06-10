package datadog.telemetry;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.telemetry.api.RequestType;
import datadog.telemetry.dependency.Dependency;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import okio.Buffer;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link TelemetryRequestBody#writeDependency} correctly serializes the optional
 * {@code metadata} array introduced for SCA Reachability.
 */
class TelemetryRequestBodyDependencyMetadataTest {

  @Test
  void writeDependency_includesMetadataArrayWhenPresent() throws IOException {
    String metadataValue =
        "{\"id\":\"GHSA-645p-88qh-w398\","
            + "\"reached\":[{\"path\":\"com.fasterxml.jackson.databind.ObjectMapper\","
            + "\"symbol\":\"<clinit>\",\"line\":1}]}";
    Dependency dep =
        new Dependency(
            "com.fasterxml.jackson.core:jackson-databind",
            "2.8.5",
            null,
            null,
            Collections.singletonList(metadataValue));

    String json = serializeDependency(dep);

    assertTrue(json.contains("\"metadata\""), "metadata array must be present");
    assertTrue(json.contains("\"type\":\"reachability\""), "type field must be reachability");
    assertTrue(json.contains("\"value\":"), "value field must be present");
    assertTrue(json.contains("GHSA-645p-88qh-w398"), "GHSA ID must appear in value");
  }

  @Test
  void writeDependency_includesAllMetadataEntriesForMultipleCves() throws IOException {
    Dependency dep =
        new Dependency(
            "com.example:lib",
            "1.0.0",
            null,
            null,
            Arrays.asList(
                "{\"id\":\"GHSA-aaa-1111-2222\",\"reached\":[]}",
                "{\"id\":\"GHSA-bbb-3333-4444\",\"reached\":[]}"));

    String json = serializeDependency(dep);

    assertTrue(json.contains("GHSA-aaa-1111-2222"), "first CVE must be present");
    assertTrue(json.contains("GHSA-bbb-3333-4444"), "second CVE must be present");
  }

  @Test
  void writeDependency_omitsMetadataFieldWhenNull() throws IOException {
    Dependency dep = new Dependency("com.example:lib", "1.0.0", null, null);

    String json = serializeDependency(dep);

    assertFalse(json.contains("\"metadata\""), "metadata field must be absent when null");
  }

  @Test
  void writeDependency_includesEmptyMetadataArrayWhenListIsEmpty() throws IOException {
    // RFC: metadata:[] (non-null, empty) means "SCA is active for this dep but no CVEs detected".
    // Must be written so the backend knows SCA is monitoring the dependency.
    Dependency dep =
        new Dependency("com.example:lib", "1.0.0", null, null, Collections.emptyList());

    String json = serializeDependency(dep);

    assertTrue(json.contains("\"metadata\":[]"), "metadata:[] must be present when list is empty");
  }

  private static String serializeDependency(Dependency dep) throws IOException {
    TelemetryRequestBody req = new TelemetryRequestBody(RequestType.APP_DEPENDENCIES_LOADED);
    req.beginRequest(false);
    req.beginDependencies();
    req.writeDependency(dep);
    req.endDependencies();
    req.endRequest();

    Buffer buf = new Buffer();
    req.writeTo(buf);
    byte[] bytes = new byte[(int) buf.size()];
    buf.read(bytes);
    return new String(bytes, StandardCharsets.UTF_8);
  }
}
