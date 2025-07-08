package com.datadog.debugger.agent;

import static com.datadog.debugger.util.ClassFileHelper.removeExtension;
import static com.datadog.debugger.util.ClassFileHelper.stripPackagePath;

import com.datadog.debugger.util.ClassFileHelper;
import datadog.trace.util.AgentTaskScheduler;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Permanent Transformer to track all Inner or Top-Level classes associated with the same SourceFile
 * (String.java) Allows to get all classes that are dependent from a source file and be able to
 * trigger {@link java.lang.instrument.Instrumentation#retransformClasses(Class[])} on them
 */
public class SourceFileTrackingTransformer implements ClassFileTransformer {
  private static final Logger LOGGER = LoggerFactory.getLogger(SourceFileTrackingTransformer.class);

  private final ClassesToRetransformFinder finder;
  private final Queue<SourceFileItem> queue = new ConcurrentLinkedQueue<>();
  private final AgentTaskScheduler scheduler = AgentTaskScheduler.INSTANCE;
  private AgentTaskScheduler.Scheduled<Runnable> scheduled;

  public SourceFileTrackingTransformer(ClassesToRetransformFinder finder) {
    this.finder = finder;
  }

  public void start() {
    scheduled = scheduler.scheduleAtFixedRate(this::flush, 0, 1, TimeUnit.SECONDS);
  }

  public void stop() {
    if (scheduled != null) {
      scheduled.cancel();
    }
  }

  void flush() {
    if (queue.isEmpty()) {
      return;
    }
    int size = queue.size();
    long start = System.nanoTime();
    SourceFileItem item;
    while ((item = queue.poll()) != null) {
      registerSourceFile(item.className, item.classfileBuffer);
    }
    LOGGER.debug(
        "flushing {} source file items in {}ms", size, (System.nanoTime() - start) / 1_000_000);
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
    queue.add(new SourceFileItem(className, classfileBuffer));
    return null;
  }

  private void registerSourceFile(String className, byte[] classfileBuffer) {
    String sourceFile = ClassFileHelper.extractSourceFile(classfileBuffer);
    if (sourceFile == null) {
      return;
    }
    if (!isExtensionAllowed(sourceFile)) {
      return;
    }
    String simpleClassName = stripPackagePath(className);
    String simpleSourceFile = removeExtension(sourceFile);
    if (simpleClassName.equals(simpleSourceFile)) {
      return;
    }
    finder.register(sourceFile, className);
  }

  private boolean isExtensionAllowed(String sourceFile) {
    return sourceFile.endsWith(".java")
        || sourceFile.endsWith(".kt")
        || sourceFile.endsWith(".scala")
        || sourceFile.endsWith(".groovy");
  }

  private static class SourceFileItem {
    final String className;
    final byte[] classfileBuffer;

    public SourceFileItem(String className, byte[] classfileBuffer) {
      this.className = className;
      this.classfileBuffer = classfileBuffer;
    }
  }
}
