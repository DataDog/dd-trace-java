package com.datadog.profiling.mlt.io;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FrameStackTest {
  private ConstantPool<String> stringPool;
  private ConstantPool<FrameElement> framePool;
  private ConstantPool<FrameStack> stackPool;

  @BeforeEach
  void setup() {
    stringPool = new ConstantPool<>();
    framePool = new ConstantPool<>();
    stackPool = new ConstantPool<>();
  }

  @Test
  void testEmptyInstance() {
    FrameStack instance = new FrameStack(0, new int[0], -1, framePool, stackPool);
    assertEquals(0, instance.depth());
    assertEquals(0, instance.frames().count());
    assertEquals(-1, instance.getHeadPtr());
    assertEquals(-1, instance.getSubtreePtr());
  }

  @Test
  void testSingleFrameInstanceFromElements() {
    FrameElement frame = new FrameElement("owner", "method", 1, stringPool);
    FrameStack instance = new FrameStack(frame, null, framePool, stackPool);
    assertNotNull(instance);
    assertEquals(1, instance.depth());
    assertNotEquals(-1, instance.getPtr());
    assertNotEquals(-1, instance.getHeadPtr());
    assertEquals(-1, instance.getSubtreePtr());

    assertEquals(1, instance.frames().count());
    assertArrayEquals(new FrameElement[] {frame}, instance.frames().toArray(FrameElement[]::new));
  }

  @Test
  void testWithSubtreeInstanceFromElements() {
    FrameElement frame = new FrameElement("owner", "method", 1, stringPool);
    FrameStack subtree = new FrameStack(frame, null, framePool, stackPool);
    FrameStack instance = new FrameStack(frame, subtree, framePool, stackPool);
    assertNotNull(instance);
    assertEquals(2, instance.depth());
    assertNotEquals(-1, instance.getPtr());
    assertNotEquals(-1, instance.getHeadPtr());
    assertNotEquals(-1, instance.getSubtreePtr());

    assertEquals(2, instance.frames().count());
    assertArrayEquals(
        new FrameElement[] {frame, frame}, instance.frames().toArray(FrameElement[]::new));
  }

  @Test
  void testInvalidFrameInstanceFromPtrs() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new FrameStack(0, new int[0], 10, framePool, stackPool));
  }

  @Test
  void testSingleFrameInstanceFromPtrs() {
    int stackPtr = 0;
    FrameElement frame = new FrameElement("owner", "method", 1, stringPool);
    int framePtr = framePool.get(frame);
    FrameStack instance = new FrameStack(stackPtr, new int[] {framePtr}, -1, framePool, stackPool);
    assertNotNull(instance);
    assertEquals(1, instance.depth());
    assertEquals(stackPtr, instance.getPtr());
    assertEquals(framePtr, instance.getHeadPtr());
    assertEquals(-1, instance.getSubtreePtr());

    assertEquals(1, instance.frames().count());
    assertArrayEquals(new FrameElement[] {frame}, instance.frames().toArray(FrameElement[]::new));
  }

  @Test
  void testWithSubtreeInstanceFromPtrs() {
    int stackSubtreePtr = 0;
    int stackPtr = 1;
    FrameElement frame = new FrameElement("owner", "method", 1, stringPool);
    int framePtr = framePool.get(frame);
    FrameStack subtree =
        new FrameStack(stackSubtreePtr, new int[] {framePtr}, -1, framePool, stackPool);
    stackPool.insert(stackSubtreePtr, subtree);

    FrameStack instance =
        new FrameStack(stackPtr, new int[] {framePtr}, stackSubtreePtr, framePool, stackPool);
    assertNotNull(instance);
    assertEquals(2, instance.depth());
    assertEquals(1, instance.getPtr());
    assertEquals(framePtr, instance.getHeadPtr());
    assertEquals(stackSubtreePtr, instance.getSubtreePtr());

    assertEquals(2, instance.frames().count());
    assertArrayEquals(
        new FrameElement[] {frame, frame}, instance.frames().toArray(FrameElement[]::new));
  }
}
