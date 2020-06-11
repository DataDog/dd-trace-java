package com.datadog.mlt.io;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FrameSequenceTest {
  private ConstantPool<String> stringPool;
  private ConstantPool<FrameElement> framePool;
  private ConstantPool<FrameSequence> stackPool;

  @BeforeEach
  void setup() {
    stringPool = new ConstantPool<>();
    framePool = new ConstantPool<>();
    stackPool = new ConstantPool<>();
  }

  @Test
  void testEmptyInstance() {
    FrameSequence instance = new FrameSequence(0, new int[0], -1, framePool, stackPool);
    assertEquals(0, instance.length());
    assertEquals(0, instance.frames().count());
    assertEquals(-1, instance.getHeadCpIndex());
    assertEquals(-1, instance.getSubsequenceCpIndex());
  }

  @Test
  void testSingleFrameInstanceFromElements() {
    FrameElement frame = new FrameElement("owner", "method", 1, stringPool);
    FrameSequence instance = new FrameSequence(frame, null, framePool, stackPool);
    assertNotNull(instance);
    assertEquals(1, instance.length());
    assertNotEquals(-1, instance.getCpIndex());
    assertNotEquals(-1, instance.getHeadCpIndex());
    assertEquals(-1, instance.getSubsequenceCpIndex());

    assertEquals(1, instance.frames().count());
    assertArrayEquals(new FrameElement[] {frame}, instance.frames().toArray(FrameElement[]::new));
  }

  @Test
  void testWithSubtreeInstanceFromElements() {
    FrameElement frame = new FrameElement("owner", "method", 1, stringPool);
    FrameSequence subtree = new FrameSequence(frame, null, framePool, stackPool);
    FrameSequence instance = new FrameSequence(frame, subtree, framePool, stackPool);
    assertNotNull(instance);
    assertEquals(2, instance.length());
    assertNotEquals(-1, instance.getCpIndex());
    assertNotEquals(-1, instance.getHeadCpIndex());
    assertNotEquals(-1, instance.getSubsequenceCpIndex());

    assertEquals(2, instance.frames().count());
    assertArrayEquals(
        new FrameElement[] {frame, frame}, instance.frames().toArray(FrameElement[]::new));
  }

  @Test
  void testInvalidFrameInstanceFromPtrs() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new FrameSequence(0, new int[0], 10, framePool, stackPool));
  }

  @Test
  void testSingleFrameInstanceFromPtrs() {
    int stackPtr = 0;
    FrameElement frame = new FrameElement("owner", "method", 1, stringPool);
    int framePtr = framePool.getOrInsert(frame);
    FrameSequence instance =
        new FrameSequence(stackPtr, new int[] {framePtr}, -1, framePool, stackPool);
    assertNotNull(instance);
    assertEquals(1, instance.length());
    assertEquals(stackPtr, instance.getCpIndex());
    assertEquals(framePtr, instance.getHeadCpIndex());
    assertEquals(-1, instance.getSubsequenceCpIndex());

    assertEquals(1, instance.frames().count());
    assertArrayEquals(new FrameElement[] {frame}, instance.frames().toArray(FrameElement[]::new));
  }

  @Test
  void testWithSubtreeInstanceFromPtrs() {
    int stackSubtreePtr = 0;
    int stackPtr = 1;
    FrameElement frame = new FrameElement("owner", "method", 1, stringPool);
    int framePtr = framePool.getOrInsert(frame);
    FrameSequence subtree =
        new FrameSequence(stackSubtreePtr, new int[] {framePtr}, -1, framePool, stackPool);
    stackPool.insert(stackSubtreePtr, subtree);

    FrameSequence instance =
        new FrameSequence(stackPtr, new int[] {framePtr}, stackSubtreePtr, framePool, stackPool);
    assertNotNull(instance);
    assertEquals(2, instance.length());
    assertEquals(1, instance.getCpIndex());
    assertEquals(framePtr, instance.getHeadCpIndex());
    assertEquals(stackSubtreePtr, instance.getSubsequenceCpIndex());

    assertEquals(2, instance.frames().count());
    assertArrayEquals(
        new FrameElement[] {frame, frame}, instance.frames().toArray(FrameElement[]::new));
  }
}
