package com.datadog.profiling.controller.oracle;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadog.profiling.controller.jfr.JfpUtils;
import java.io.IOException;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JfrMBeanHelperTest {
  private static Map<String, String> eventSettings;
  private JfrMBeanHelper instance;

  @BeforeAll
  static void setupStatic() throws Exception {
    eventSettings = JfpUtils.readJfpResources(JfpUtils.DEFAULT_JFP, null);
  }

  @BeforeEach
  void setup() throws IOException {
    instance = new JfrMBeanHelper();
  }

  @Test
  void testEventSettings() throws Exception {
    List<CompositeData> data = instance.encodeEventSettings(eventSettings);
    assertNotNull(data);
    assertTrue(data.size() <= eventSettings.size());
  }

  @Test
  void testNewRecording() throws Exception {
    String recordingName = "test-recording";
    long maxSize = 100000;
    Duration maxAge = Duration.of(30, ChronoUnit.SECONDS);

    ObjectName recordingId = instance.newRecording(recordingName, maxSize, maxAge, eventSettings);
    try {
      assertNotNull(recordingId);

      assertTrue((boolean) instance.getRecordingAttribute(recordingId, "Running"));
      assertEquals(maxAge.toMillis(), instance.getRecordingAttribute(recordingId, "MaxAge"));
      assertEquals(maxSize, instance.getRecordingAttribute(recordingId, "MaxSize"));
    } finally {
      instance.closeRecording(recordingId);
    }
  }

  @Test
  void testCloseRecording() throws Exception {
    String recordingName = "test-recording";
    long maxSize = 100000;
    Duration maxAge = Duration.of(30, ChronoUnit.SECONDS);

    ObjectName recordingId = instance.newRecording(recordingName, maxSize, maxAge, eventSettings);
    instance.closeRecording(recordingId);
    assertThrows(IOException.class, () -> instance.getRecordingAttribute(recordingId, "Running"));
  }

  @Test
  void testCloseRecordingDuplicate() throws Exception {
    String recordingName = "test-recording";
    long maxSize = 100000;
    Duration maxAge = Duration.of(30, ChronoUnit.SECONDS);

    ObjectName recordingId = instance.newRecording(recordingName, maxSize, maxAge, eventSettings);
    assertDoesNotThrow(() -> instance.closeRecording(recordingId));
    assertThrows(IOException.class, () -> instance.closeRecording(recordingId));
  }

  @Test
  void testStopRecording() throws Exception {
    String recordingName = "test-recording";
    long maxSize = 100000;
    Duration maxAge = Duration.of(30, ChronoUnit.SECONDS);

    ObjectName recordingId = instance.newRecording(recordingName, maxSize, maxAge, eventSettings);
    try {
      instance.stopRecording(recordingId);
      assertFalse((boolean) instance.getRecordingAttribute(recordingId, "Running"));
      assertTrue((boolean) instance.getRecordingAttribute(recordingId, "Stopped"));
    } finally {
      instance.closeRecording(recordingId);
    }
  }

  @Test
  void testStopRecordingDouble() throws Exception {
    String recordingName = "test-recording";
    long maxSize = 100000;
    Duration maxAge = Duration.of(30, ChronoUnit.SECONDS);

    ObjectName recordingId = instance.newRecording(recordingName, maxSize, maxAge, eventSettings);
    try {
      instance.stopRecording(recordingId);
      assertDoesNotThrow(() -> instance.stopRecording(recordingId));
    } finally {
      instance.closeRecording(recordingId);
    }
  }

  @Test
  void testStopRecordingInvalid() throws Exception {
    ObjectName recordingId =
        ObjectName.getInstance("com.oracle.jrockit:type=FlightRecording,id=1,name=test-recording");
    assertThrows(IOException.class, () -> instance.stopRecording(recordingId));
  }

  @Test
  void testOpenStreamActive() throws Exception {
    String recordingName = "test-recording";
    long maxSize = 100000;
    Duration maxAge = Duration.of(30, ChronoUnit.SECONDS);

    Date start = new Date();
    ObjectName recordingId = instance.newRecording(recordingName, maxSize, maxAge, eventSettings);
    try {
      assertThrows(IOException.class, () -> instance.openStream(recordingId, start, new Date()));
    } finally {
      instance.closeRecording(recordingId);
    }
  }

  @Test
  void testOpenStreamStopped() throws Exception {
    String recordingName = "test-recording";
    long maxSize = 100000;
    Duration maxAge = Duration.of(30, ChronoUnit.SECONDS);

    Date start = new Date();
    ObjectName recordingId = instance.newRecording(recordingName, maxSize, maxAge, eventSettings);
    try {
      instance.stopRecording(recordingId);
      assertDoesNotThrow(() -> instance.openStream(recordingId, start, new Date()));
      assertDoesNotThrow(() -> instance.openStream(recordingId, null, null));
    } finally {
      instance.closeRecording(recordingId);
    }
  }

  @Test
  void testCloseStream() throws Exception {
    String recordingName = "test-recording";
    long maxSize = 100000;
    Duration maxAge = Duration.of(30, ChronoUnit.SECONDS);

    ObjectName recordingId = instance.newRecording(recordingName, maxSize, maxAge, eventSettings);
    try {
      instance.stopRecording(recordingId);
      long streamId = instance.openStream(recordingId, null, null);

      assertDoesNotThrow(() -> instance.closeStream(streamId));
      assertThrows(IOException.class, () -> instance.closeStream(streamId));
    } finally {
      instance.closeRecording(recordingId);
    }
  }

  @Test
  void testCloseStreamInvalid() {
    assertThrows(IOException.class, () -> instance.closeStream(-1));
  }

  @Test
  void testReadStream() throws Exception {
    String recordingName = "test-recording";
    long maxSize = 100000;
    Duration maxAge = Duration.of(30, ChronoUnit.SECONDS);

    ObjectName recordingId = instance.newRecording(recordingName, maxSize, maxAge, eventSettings);
    try {
      instance.stopRecording(recordingId);
      long streamId = instance.openStream(recordingId, null, null);

      int len = 0;
      byte[] buffer = null;
      while ((buffer = instance.readStream(streamId)) != null) {
        len += buffer.length;
      }
      assertTrue(len > 0);
    } finally {
      instance.closeRecording(recordingId);
    }
  }

  @Test
  void testStreamInvalid() {
    assertThrows(IOException.class, () -> instance.readStream(-1));
  }

  @Test
  void testCloneRecordingInvalid() throws Exception {
    ObjectName recordingId =
        ObjectName.getInstance("com.oracle.jrockit:type=FlightRecording,id=1,name=test-recording");
    assertThrows(IOException.class, () -> instance.cloneRecording(recordingId));
  }

  @Test
  void testCloneRecording() throws Exception {
    String recordingName = "test-recording";
    long maxSize = 100000;
    Duration maxAge = Duration.of(30, ChronoUnit.SECONDS);

    ObjectName recordingId = instance.newRecording(recordingName, maxSize, maxAge, eventSettings);
    try {
      ObjectName cloned = instance.cloneRecording(recordingId);
      try {
        assertNotEquals(recordingId, cloned);
        assertTrue((boolean) instance.getRecordingAttribute(recordingId, "Running"));
        assertFalse((boolean) instance.getRecordingAttribute(cloned, "Running"));
      } finally {
        instance.closeRecording(cloned);
      }
    } finally {
      instance.closeRecording(recordingId);
    }
  }

  @Test
  void testParseDuration() {
    assertEquals(
        Duration.of(10, ChronoUnit.SECONDS),
        JfrMBeanHelper.parseDuration("10", ChronoUnit.SECONDS));
    assertEquals(Duration.of(10, ChronoUnit.NANOS), JfrMBeanHelper.parseDuration("10"));
    Map<String, ChronoUnit> mapping = new HashMap<>();
    mapping.put("ns", ChronoUnit.NANOS);
    mapping.put("us", ChronoUnit.MICROS);
    mapping.put("ms", ChronoUnit.MILLIS);
    mapping.put("s", ChronoUnit.SECONDS);
    mapping.put("m", ChronoUnit.MINUTES);

    long val = 5;
    for (Map.Entry<String, ChronoUnit> entry : mapping.entrySet()) {
      assertEquals(
          Duration.of(val, entry.getValue()),
          JfrMBeanHelper.parseDuration(val + " " + entry.getKey()));
      assertEquals(
          Duration.of(val, entry.getValue()),
          JfrMBeanHelper.parseDuration(val + "\t     " + entry.getKey(), ChronoUnit.HOURS));
      assertThrows(
          NumberFormatException.class, () -> JfrMBeanHelper.parseDuration(val + entry.getKey()));
    }
  }
}
