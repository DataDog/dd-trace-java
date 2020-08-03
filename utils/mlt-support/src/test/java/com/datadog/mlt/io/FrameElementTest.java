package com.datadog.mlt.io;

import static org.junit.jupiter.api.Assertions.*;

import nl.jqno.equalsverifier.EqualsVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FrameElementTest {
  private ConstantPool<String> stringPool;
  private ConstantPool<FrameElement> framePool;

  @BeforeEach
  void setUp() {
    stringPool = new ConstantPool<>();
    framePool = new ConstantPool<>();
  }

  @Test
  void instanceNullStringPool() {
    assertThrows(
        NullPointerException.class, () -> new FrameElement("owner", "method", 1, null, framePool));
  }

  @Test
  void instanceFromValues() {
    String owner = "owner";
    String method = "method";
    int line = 1;
    FrameElement instance = new FrameElement(owner, method, line, stringPool, framePool);
    assertEquals(owner, instance.getOwner());
    assertEquals(method, instance.getMethod());
    assertEquals(line, instance.getLine());
  }

  @Test
  void instanceFromPointers() {
    String owner = "owner";
    String method = "method";
    int line = 1;
    int ownerPtr = stringPool.getOrInsert(owner);
    int methodPtr = stringPool.getOrInsert(method);

    FrameElement instance = new FrameElement(-1, ownerPtr, methodPtr, line, stringPool, framePool);
    assertEquals(owner, instance.getOwner());
    assertEquals(method, instance.getMethod());
    assertEquals(line, instance.getLine());
  }

  @Test
  public void equalsContract() {
    EqualsVerifier.forClass(FrameElement.class).withIgnoredFields("stringPool", "cpIndex").verify();
  }
}
