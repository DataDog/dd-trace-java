package com.datadog.debugger.agent;

import com.datadog.debugger.util.ClassFileHelper;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Permanent Transformer to track all Inner or Top-Level classes associated with the same SourceFile
 * (String.java) Allows to get all classes that are dependent from a source file and be able to
 * trigger {@link java.lang.instrument.Instrumentation#retransformClasses(Class[])} on them
 */
public class SourceFileTrackingTransformer implements ClassFileTransformer {
  public static final SourceFileTrackingTransformer INSTANCE = new SourceFileTrackingTransformer();

  private final ConcurrentMap<String, List<String>> classNamesBySourceFile =
      new ConcurrentHashMap<>();

  @Override
  public byte[] transform(
      ClassLoader loader,
      String className,
      Class<?> classBeingRedefined,
      ProtectionDomain protectionDomain,
      byte[] classfileBuffer)
      throws IllegalClassFormatException {
    if (className == null) {
      return null;
    }
    String sourceFile = ClassFileHelper.extractSourceFile(classfileBuffer);
    if (sourceFile == null) {
      return null;
    }
    String simpleClassName = className.substring(className.lastIndexOf('/') + 1);
    String simpleSourceFile = sourceFile.substring(0, sourceFile.lastIndexOf('.'));
    if (simpleClassName.equals(simpleSourceFile)) {
      return null;
    }
    // store only the class name that are different from SourceFile name
    // (Inner or non-public Top-Level classes)
    classNamesBySourceFile.compute(
        sourceFile,
        (key, list) -> {
          if (list == null) {
            list = new ArrayList<>();
          }
          list.add(className);
          return list;
        });
    return null;
  }

  public List<String> getClassNameBySourceFile(String sourceFile) {
    return classNamesBySourceFile.get(sourceFile);
  }
}
