package com.datadog.profiling.controller.openjdk.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import datadog.environment.JavaVirtualMachine;
import datadog.environment.OperatingSystem;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.tabletest.junit.TableTest;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class SmapEntryCacheTest {
  @TempDir Path directory;
  private String previousEncoding;

  @BeforeEach
  void saveNativeEncoding() {
    previousEncoding = System.getProperty("native.encoding");
  }

  @AfterEach
  void restoreNativeEncoding() {
    if (previousEncoding == null) {
      System.clearProperty("native.encoding");
    } else {
      System.setProperty("native.encoding", previousEncoding);
    }
  }

  @TableTest({
    "Scenario               | Native charset | Path charset | Pathname             | Expected pathname      ",
    "ASCII path             | UTF-8          | US-ASCII     | /tmp/lib.so          | /tmp/lib.so            ",
    "UTF-8 path             | UTF-8          | UTF-8        | /tmp/café.so         | /tmp/café.so           ",
    "UTF-8 on ASCII host    | US-ASCII       | UTF-8        | /tmp/café.so         | /tmp/café.so           ",
    "UTF-8 on Latin-1 host  | ISO-8859-1     | UTF-8        | /tmp/café.so         | /tmp/café.so           ",
    "native Latin-1 path    | ISO-8859-1     | ISO-8859-1   | /tmp/café.so         | /tmp/café.so           ",
    "native Windows-1252    | windows-1252   | windows-1252 | /tmp/café-€.so       | /tmp/café-€.so         ",
    "Latin-1 on UTF-8 host  | UTF-8          | ISO-8859-1   | /tmp/café.so         | /tmp/caf\\xe9.so       ",
    "Latin-1 on ASCII host  | US-ASCII       | ISO-8859-1   | /tmp/café.so         | /tmp/caf\\xe9.so       ",
    "highest byte           | US-ASCII       | ISO-8859-1   | /tmp/ÿ.so            | /tmp/\\xff.so          ",
    "unmappable native byte | windows-1252   | ISO-8859-1   | /tmp/\u0081.so       | /tmp/\\x81.so          ",
    "literal escape text    | UTF-8          | UTF-8        | /tmp/literal\\xe9.so | /tmp/literal\\\\xe9.so ",
    "escaped byte and slash | US-ASCII       | ISO-8859-1   | /tmp/café\\xe9.so    | /tmp/caf\\xe9\\\\xe9.so"
  })
  void readsPathnameWithoutReplacingBytes(
      String nativeCharset, String pathCharset, String pathname, String expectedPathname)
      throws Exception {
    System.setProperty("native.encoding", nativeCharset);
    Path smaps = directory.resolve("smaps");
    Files.write(smaps, entry(pathname, Charset.forName(pathCharset)));

    SmapEntryCache cache = new SmapEntryCache(Duration.ofHours(1), smaps);
    cache.invalidate();
    List<SmapEntryEvent> events = cache.getEvents();

    assertEquals(1, events.size());
    assertEquals(expectedPathname, events.get(0).pathname);
    assertEquals(4096, events.get(0).size);
  }

  @Test
  void preservesMixedPathEncodingsInRecording() throws Exception {
    System.setProperty("native.encoding", "UTF-8");
    ByteArrayOutputStream contents = new ByteArrayOutputStream();
    contents.write(entry("/tmp/café.so", StandardCharsets.UTF_8));
    contents.write(entry("/tmp/café.so", StandardCharsets.ISO_8859_1));
    Path smaps = directory.resolve("smaps");
    Files.write(smaps, contents.toByteArray());
    SmapEntryCache cache = new SmapEntryCache(Duration.ofHours(1), smaps);
    cache.invalidate();
    List<SmapEntryEvent> events = cache.getEvents();
    assertEquals(2, events.size());

    Path recordingPath = directory.resolve("smaps.jfr");
    try (Recording recording = new Recording()) {
      recording.enable(SmapEntryEvent.class);
      recording.start();
      events.forEach(SmapEntryEvent::commit);
      recording.stop();
      recording.dump(recordingPath);
    }

    List<String> pathnames = new ArrayList<>();
    try (RecordingFile recording = new RecordingFile(recordingPath)) {
      while (recording.hasMoreEvents()) {
        pathnames.add(recording.readEvent().getString("pathname"));
      }
    }
    assertEquals(Arrays.asList("/tmp/café.so", "/tmp/caf\\xe9.so"), pathnames);
  }

  private static byte[] entry(String pathname, Charset charset) {
    return ("00400000-00401000 r-xp 00000000 08:02 1234 " + pathname + "\nSize: 4 kB\n")
        .getBytes(charset);
  }

  @Test
  void preservesDecodedPathsWhenLaterMappingIsMalformed() throws Exception {
    System.setProperty("native.encoding", "UTF-8");
    ByteArrayOutputStream contents = new ByteArrayOutputStream();
    contents.write(entry("/tmp/café.so", StandardCharsets.UTF_8));
    contents.write("00400000-\n".getBytes(StandardCharsets.US_ASCII));
    Path smaps = directory.resolve("smaps");
    Files.write(smaps, contents.toByteArray());
    SmapEntryCache cache = new SmapEntryCache(Duration.ofHours(1), smaps);
    cache.invalidate();

    assertEquals("/tmp/café.so", cache.getEvents().get(0).pathname);
  }

  @Test
  void getEvents() throws Exception {
    // SMAPs are only available on Linux
    assumeTrue(OperatingSystem.isLinux());
    // We need at least Java 22 for the annotated regions
    assumeTrue(JavaVirtualMachine.isJavaVersionAtLeast(22));
    SmapEntryCache smapEntryCache =
        new SmapEntryCache(Duration.ofHours(1)); // set up a really long expiration duration
    List<SmapEntryEvent> events1 = smapEntryCache.getEvents();
    List<SmapEntryEvent> events2 = smapEntryCache.getEvents();
    // the cache is using double buffered event list so we can use identity comparison
    assertSame(events1, events2);

    smapEntryCache.invalidate(); // pretend expiring the cache
    events1 = smapEntryCache.getEvents();
    assertNotSame(events1, events2);
  }
}
