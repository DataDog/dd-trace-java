package datadog.trace.agent.tooling;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.flare.TracerFlare;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class InstrumenterFlareTest {

  @AfterEach
  void cleanup() {
    InstrumenterFlare.resetTransformationErrors();
  }

  @Test
  void addsBoundedDeduplicatedTransformationErrors() throws IOException {
    InstrumenterFlare.recordTransformationError("repeated error");
    InstrumenterFlare.recordTransformationError("repeated error");
    for (int error = 0; error < 63; error++) {
      InstrumenterFlare.recordTransformationError("unique error " + error);
    }
    InstrumenterFlare.recordTransformationError("overflow error");

    InstrumenterFlare.register();
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
      TracerFlare.addReportsToFlare(zip);
    }

    String errors = readEntry(bytes.toByteArray(), "instrumenter_errors.txt");
    assertNotNull(errors);
    assertTrue(errors.contains("count=2 repeated error"));
    assertTrue(errors.contains("count=1 unique error 62"));
    assertFalse(errors.contains("overflow error"));
    assertTrue(errors.contains("dropped_error_count=1"));
  }

  private static String readEntry(byte[] zipBytes, String entryName) throws IOException {
    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
      ZipEntry entry;
      byte[] buffer = new byte[1024];
      while ((entry = zip.getNextEntry()) != null) {
        if (entryName.equals(entry.getName())) {
          ByteArrayOutputStream contents = new ByteArrayOutputStream();
          int read;
          while ((read = zip.read(buffer)) >= 0) {
            contents.write(buffer, 0, read);
          }
          return new String(contents.toByteArray(), StandardCharsets.UTF_8);
        }
      }
      return null;
    }
  }
}
