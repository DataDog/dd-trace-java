package com.datadog.profiling.controller;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.*;

class RecordingInputStreamTest {

  @Test
  void isEmpty() throws Exception {
    RecordingInputStream instance = new RecordingInputStream(new ByteArrayInputStream(new byte[0]));
    assertTrue(instance.isEmpty());
  }

  @Test
  void isNotEmpty() throws Exception {
    RecordingInputStream instance = new RecordingInputStream(new ByteArrayInputStream(new byte[]{1, 2 ,3}));
    assertFalse(instance.isEmpty());
  }
}
