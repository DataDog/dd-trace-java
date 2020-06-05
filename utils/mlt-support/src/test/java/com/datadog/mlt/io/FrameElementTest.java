package com.datadog.mlt.io;

import static org.junit.jupiter.api.Assertions.*;

import nl.jqno.equalsverifier.EqualsVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FrameElementTest {
  private ConstantPool<String> stringPool;

  @BeforeEach
  void setUp() {
    stringPool = new ConstantPool<>();
  }

  @Test
  void instanceNullStringPool() {
    assertThrows(NullPointerException.class, () -> new FrameElement("owner", "method", 1, null));
  }

  @Test
  void instanceFromValues() {
    String owner = "owner";
    String method = "method";
    int line = 1;
    FrameElement instance = new FrameElement(owner, method, line, stringPool);
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

    FrameElement instance = new FrameElement(ownerPtr, methodPtr, line, stringPool);
    assertEquals(owner, instance.getOwner());
    assertEquals(method, instance.getMethod());
    assertEquals(line, instance.getLine());
  }

  @Test
  public void equalsContract() {
    EqualsVerifier.forClass(FrameElement.class).withIgnoredFields("stringPool").verify();
  }
}
