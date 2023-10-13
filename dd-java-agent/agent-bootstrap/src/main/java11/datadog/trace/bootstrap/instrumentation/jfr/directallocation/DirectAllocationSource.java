package datadog.trace.bootstrap.instrumentation.jfr.directallocation;

public enum DirectAllocationSource {
  ALLOCATE_DIRECT,
  MMAP,
  JNI;

  static final DirectAllocationSource[] VALUES = values();
}
