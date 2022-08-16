package com.datadog.profiling.context;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LEB128SupportTest {
  private LEB128Support instance;

  @BeforeEach
  void setup() {
    instance = new LEB128Support();
  }

  @Test
  void align() {
    assertEquals(0, instance.align(0, 3));
    assertEquals(3, instance.align(1, 3));
    assertEquals(3, instance.align(3, 3));
  }

  @Test
  void varintSize() {
    ByteBuffer buffer = ByteBuffer.allocate(10);
    long value = 0L;
    instance.putVarint(buffer, value);
    int size = instance.varintSize(value);
    assertEquals(1, size);
    assertEquals(buffer.position(), size);

    buffer.rewind();
    value = 1L;
    instance.putVarint(buffer, value);
    size = instance.varintSize(value);
    assertEquals(1, size);
    assertEquals(buffer.position(), size);

    value = 0x40;
    for (int i = 1; i < 10; i++) {
      size = instance.varintSize(value);
      buffer.rewind();
      instance.putVarint(buffer, value);
      assertEquals(buffer.position(), size);
      assertEquals(i, size);

      if (i == 9) {
        value = value << 1;
        size = instance.varintSize(value);
        buffer.rewind();
        instance.putVarint(buffer, value);
        assertEquals(buffer.position(), size);
        assertEquals(i, size);
      } else {
        value = value << 7;
      }
    }

    value = 8738041250962L;
    size = instance.varintSize(value);
    buffer.rewind();
    instance.putVarint(buffer, value);
    assertEquals(buffer.position(), size);
  }

  @Test
  void longSize() {
    long value = 0L;
    int size = instance.longSize(value);
    assertEquals(1, size);

    value = 0x80;
    for (int i = 1; i < 9; i++) {
      size = instance.longSize(value);
      assertEquals(i, size);

      value = value << 8;
    }
  }
}
