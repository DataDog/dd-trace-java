package com.datadog.debugger.agent;

import static com.datadog.debugger.util.ClassFileHelper.removeExtension;
import static com.datadog.debugger.util.ClassFileHelper.stripPackagePath;

import com.datadog.debugger.util.ClassFileHelper;
import com.datadog.debugger.util.ClassNameFiltering;
import datadog.trace.api.Config;
import datadog.trace.util.AgentTaskScheduler;
import datadog.trace.util.Strings;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Permanent Transformer to track all Inner or Top-Level classes associated with the same SourceFile
 * (String.java) Allows to get all classes that are dependent from a source file and be able to
 * trigger {@link java.lang.instrument.Instrumentation#retransformClasses(Class[])} on them
 */
public class SourceFileTrackingTransformer implements ClassFileTransformer {
  private static final Logger LOGGER = LoggerFactory.getLogger(SourceFileTrackingTransformer.class);
  static final int MAX_QUEUE_SIZE = 16 * 1024;

  private final ClassesToRetransformFinder finder;
  private final Queue<SourceFileItem> queue = new ConcurrentLinkedQueue<>();
  private final AgentTaskScheduler scheduler = AgentTaskScheduler.get();
  private final AtomicInteger queueSize = new AtomicInteger(0);
  private AgentTaskScheduler.Scheduled<Runnable> scheduled;
  // this field MUST only be used in flush() calling thread
  private ClassNameFiltering classNameFilter;

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
    if (classNameFilter == null) {
      // init class name filter once here to parse the config in background thread and avoid
      // startup latency on main thread. The field classNameFilter MUST only be used in this thread
      classNameFilter = new ClassNameFiltering(Config.get());
    }
    if (queue.isEmpty()) {
      return;
    }
    int itemCount = 0;
    long start = System.nanoTime();
    SourceFileItem item;
    while ((item = queue.poll()) != null) {
      queueSize.decrementAndGet();
      registerSourceFile(item.className, item.classfileBuffer);
      itemCount++;
    }
    LOGGER.debug(
        "flushing {} source file items in {}ms, totalentries: {}",
        itemCount,
        (System.nanoTime() - start) / 1_000_000,
        finder.getClassNamesBySourceFile().size());
  }

  int getQueueSize() {
    return queueSize.get();
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
    if (queueSize.get() >= MAX_QUEUE_SIZE) {
      LOGGER.debug("SourceFile Tracking queue full, dropping class: {}", className);
      return null;
    }
    queue.add(new SourceFileItem(className, classfileBuffer));
    queueSize.incrementAndGet();
    return null;
  }

  private void registerSourceFile(String className, byte[] classfileBuffer) {
    String javaClassName = Strings.getClassName(className);
    if (classNameFilter.isExcluded(javaClassName)) {
      return;
    }
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
