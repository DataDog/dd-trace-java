package com.datadog.profiling.utils;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class LibraryHelperTest {
  @Test
  void testExistingLib() {
    File libFile = assertDoesNotThrow(() -> LibraryHelper.libraryFromClasspath("/libDummy.so"));
    assertNotNull(libFile);
    assertTrue(libFile.exists());
  }

  @Test
  void testNonexistentLib() {
    assertThrows(IOException.class, () -> LibraryHelper.libraryFromClasspath("/libNone.so"));
  }

  @Test
  void testRelativePath() {
    assertThrows(
        IllegalArgumentException.class, () -> LibraryHelper.libraryFromClasspath("no_lib.so"));
  }

  @Test
  void testTooShortPath() {
    assertThrows(IllegalArgumentException.class, () -> LibraryHelper.libraryFromClasspath("/a.so"));
  }

  @Test
  void testNoExtension() {
    assertThrows(IOException.class, () -> LibraryHelper.libraryFromClasspath("/libNone"));
  }
}
