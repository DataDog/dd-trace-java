package datadog.trace.api.flare;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import datadog.trace.test.util.DDJavaSpecification;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class TracerFlareTest extends DDJavaSpecification {

  // give each mock its own type - we use that to disambiguate reporters
  interface Reporter1 extends TracerFlare.Reporter {}

  interface Reporter2 extends TracerFlare.Reporter {}

  @Test
  void testTracerFlare() throws IOException {
    TracerFlare.addReporter(zip -> {}); // exercises default methods
    Reporter1 textReporter = mock(Reporter1.class);
    TracerFlare.addReporter(textReporter);
    Reporter2 binaryReporter = mock(Reporter2.class);
    TracerFlare.addReporter(binaryReporter);
    byte[] binaryContent = {0, 1, 2, 3, 4, 5, 6, 7};
    // record lifecycle phases across both reporters to verify each phase completes for every
    // reporter before the next phase starts (reporters themselves run in no particular order)
    List<String> phases = new ArrayList<>();
    recordPrepareAndCleanup(textReporter, phases);
    recordPrepareAndCleanup(binaryReporter, phases);
    doAnswer(
            invocation -> {
              phases.add("add");
              ZipOutputStream zos = invocation.getArgument(0);
              TracerFlare.addText(zos, "test.txt", "example text");
              throw new IllegalStateException("txt (expected)"); // should not stop flare
            })
        .when(textReporter)
        .addReportToFlare(any());
    doAnswer(
            invocation -> {
              phases.add("add");
              ZipOutputStream zos = invocation.getArgument(0);
              TracerFlare.addBinary(zos, "test.bin", binaryContent);
              throw new IllegalStateException("bin (expected)"); // should not stop flare
            })
        .when(binaryReporter)
        .addReportToFlare(any());

    Map<String, Object> entries = buildAndExtractZip();

    verify(textReporter).prepareForFlare();
    verify(binaryReporter).prepareForFlare();
    verify(textReporter).addReportToFlare(any());
    verify(binaryReporter).addReportToFlare(any());
    verify(textReporter).cleanupAfterFlare();
    verify(binaryReporter).cleanupAfterFlare();
    verifyNoMoreInteractions(textReporter, binaryReporter);
    assertEquals(asList("prepare", "prepare", "add", "add", "cleanup", "cleanup"), phases);

    assertEquals(3, entries.size());
    assertEquals("example text", entries.get("test.txt"));
    assertArrayEquals(binaryContent, (byte[]) entries.get("test.bin"));
    String errors = (String) entries.get("flare_errors.txt");
    assertTrue(
        errors.matches("(java.lang.IllegalStateException: (bin|txt) \\(expected\\)\n){2}"), errors);
  }

  private static void recordPrepareAndCleanup(TracerFlare.Reporter reporter, List<String> phases) {
    doAnswer(invocation -> phases.add("prepare")).when(reporter).prepareForFlare();
    doAnswer(invocation -> phases.add("cleanup")).when(reporter).cleanupAfterFlare();
  }

  private static Map<String, Object> buildAndExtractZip() throws IOException {
    TracerFlare.prepareForFlare();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(out)) {
      TracerFlare.addReportsToFlare(zip);
    } finally {
      TracerFlare.cleanupAfterFlare();
    }

    Map<String, Object> entries = new HashMap<>();

    ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()));
    ZipEntry entry;
    while ((entry = zip.getNextEntry()) != null) {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      byte[] buffer = new byte[1024];
      int read;
      while ((read = zip.read(buffer)) != -1) {
        bytes.write(buffer, 0, read);
      }
      entries.put(
          entry.getName(),
          entry.getName().endsWith(".bin")
              ? bytes.toByteArray()
              : new String(bytes.toByteArray(), UTF_8));
    }

    return entries;
  }
}
