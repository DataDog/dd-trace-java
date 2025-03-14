package com.datadog.debugger.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

public class ClassFileHelperTest {

  @Test
  public void extractSourceFile() {
    assertEquals(
        "JDK8.java",
        ClassFileHelper.extractSourceFile(
            readClassFileBytes("/com/datadog/debugger/classfiles/JDK8.class")));
    assertEquals(
        "JDK23.java",
        ClassFileHelper.extractSourceFile(
            readClassFileBytes("/com/datadog/debugger/classfiles/JDK23.class")));
    // big classfile (80KB)
    assertEquals(
        "CommandLine.java",
        ClassFileHelper.extractSourceFile(
            readClassFileBytes("/com/datadog/debugger/classfiles/CommandLine.class")));
  }

  private static byte[] readClassFileBytes(String fileName) {
    try {
      return Files.readAllBytes(Paths.get(ClassFileHelperTest.class.getResource(fileName).toURI()));
    } catch (IOException | URISyntaxException e) {
      throw new RuntimeException(e);
    }
  }
}
