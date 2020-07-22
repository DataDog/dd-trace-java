package com.datadog.mlt.io;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
    assertEquals(0, instance.framesFromLeaves().count());
    assertEquals(-1, instance.getHeadCpIndex());
    assertEquals(-1, instance.getSubsequenceCpIndex());
  }

  @Test
  void testSingleFrameInstanceFromElements() {
    FrameElement frame = new FrameElement("owner", "method", 1, stringPool, framePool);
    FrameSequence instance = new FrameSequence(frame, null, framePool, stackPool);
    assertNotNull(instance);
    assertEquals(1, instance.length());
    assertNotEquals(-1, instance.getCpIndex());
    assertNotEquals(-1, instance.getHeadCpIndex());
    assertEquals(-1, instance.getSubsequenceCpIndex());
    assertEquals(1, instance.framesFromLeaves().count());
    assertArrayEquals(
        new FrameElement[] {frame}, instance.framesFromLeaves().toArray(FrameElement[]::new));
  }

  @Test
  void testWithSubtreeInstanceFromElements() {
    FrameElement frame1 = new FrameElement("owner1", "method1", 1, stringPool, framePool);
    FrameElement frame2 = new FrameElement("owner2", "method2", 2, stringPool, framePool);
    FrameSequence subtree = new FrameSequence(frame1, null, framePool, stackPool);
    FrameSequence instance = new FrameSequence(frame2, subtree, framePool, stackPool);
    assertNotNull(instance);
    assertEquals(2, instance.length());
    assertNotEquals(-1, instance.getCpIndex());
    assertNotEquals(-1, instance.getHeadCpIndex());
    assertNotEquals(-1, instance.getSubsequenceCpIndex());

    assertEquals(2, instance.framesFromLeaves().count());
    assertArrayEquals(
        new FrameElement[] {frame2, frame1},
        instance.framesFromLeaves().toArray(FrameElement[]::new));
    assertArrayEquals(
        new FrameElement[] {frame1, frame2},
        instance.framesFromRoot().toArray(FrameElement[]::new));
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
    FrameElement frame = new FrameElement("owner", "method", 1, stringPool, framePool);
    int framePtr = framePool.getOrInsert(frame);
    FrameSequence instance =
        new FrameSequence(stackPtr, new int[] {framePtr}, -1, framePool, stackPool);
    assertNotNull(instance);
    assertEquals(1, instance.length());
    assertEquals(stackPtr, instance.getCpIndex());
    assertEquals(framePtr, instance.getHeadCpIndex());
    assertEquals(-1, instance.getSubsequenceCpIndex());

    assertEquals(1, instance.framesFromLeaves().count());
    assertArrayEquals(
        new FrameElement[] {frame}, instance.framesFromLeaves().toArray(FrameElement[]::new));
  }

  @Test
  void testWithSubtreeInstanceFromPtrs() {
    int stackSubtreePtr = 0;
    int stackPtr = 1;
    FrameElement frame = new FrameElement("owner", "method", 1, stringPool, framePool);
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

    assertEquals(2, instance.framesFromLeaves().count());
    assertArrayEquals(
        new FrameElement[] {frame, frame},
        instance.framesFromLeaves().toArray(FrameElement[]::new));
  }
}
