package com.datadog.debugger.agent;

import static com.datadog.debugger.util.ClassFileHelper.removeExtension;
import static com.datadog.debugger.util.ClassFileHelper.stripPackagePath;

import com.datadog.debugger.util.ClassFileHelper;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;

/**
 * Permanent Transformer to track all Inner or Top-Level classes associated with the same SourceFile
 * (String.java) Allows to get all classes that are dependent from a source file and be able to
 * trigger {@link java.lang.instrument.Instrumentation#retransformClasses(Class[])} on them
 */
public class SourceFileTrackingTransformer implements ClassFileTransformer {
  private final ClassesToRetransformFinder finder;

  public SourceFileTrackingTransformer(ClassesToRetransformFinder finder) {
    this.finder = finder;
  }

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
    String simpleClassName = stripPackagePath(className);
    String simpleSourceFile = removeExtension(sourceFile);
    if (simpleClassName.equals(simpleSourceFile)) {
      return null;
    }
    finder.register(sourceFile, className);
    return null;
  }
}
