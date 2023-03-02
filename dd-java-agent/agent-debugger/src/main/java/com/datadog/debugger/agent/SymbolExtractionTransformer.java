package com.datadog.debugger.agent;

import com.datadog.debugger.instrumentation.SymbolExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.ProtectionDomain;

public class SymbolExtractionTransformer implements ClassFileTransformer {

  private static final Logger LOGGER = LoggerFactory.getLogger(SymbolExtractionTransformer.class);

  public SymbolExtractionTransformer() {
  }

  @Override
  public byte[] transform(
      ClassLoader loader,
      String className,
      Class<?> classBeingRedefined,
      ProtectionDomain protectionDomain,
      byte[] classfileBuffer
  ) {
    if (className.contains("Oskar")) {
      dumpOriginalClassFile(className, classfileBuffer);
      SymbolExtractor symbolExtractor = new SymbolExtractor(className, classfileBuffer);
      dump(symbolExtractor.getClassExtraction());
    }
    return null;
  }

  private void dump(SymbolExtractor.ClassExtraction classExtraction) {
    System.out.println(classExtraction);
  }

  private void dumpOriginalClassFile(String className, byte[] classfileBuffer) {
    Path classFilePath = dumpClassFile(className + "_orig", classfileBuffer);
    if (classFilePath != null) {
      LOGGER.debug("Original class saved as: {}", classFilePath.toString());
    }
  }

  private static Path dumpClassFile(String className, byte[] classfileBuffer) {
    try {
      Path classFilePath = Paths.get("/tmp/debugger/" + className + ".class");
      Files.createDirectories(classFilePath.getParent());
      Files.write(classFilePath, classfileBuffer, StandardOpenOption.CREATE);
      return classFilePath;
    } catch (IOException e) {
      LOGGER.error("", e);
      return null;
    }
  }

}
