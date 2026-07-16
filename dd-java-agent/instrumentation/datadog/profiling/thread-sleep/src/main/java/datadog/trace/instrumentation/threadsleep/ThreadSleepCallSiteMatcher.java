// Copyright 2026 Datadog, Inc.
package datadog.trace.instrumentation.threadsleep;

import static datadog.trace.instrumentation.threadsleep.ThreadSleepCallSiteMethodVisitor.SLEEP_DURATION_DESC;
import static datadog.trace.instrumentation.threadsleep.ThreadSleepCallSiteMethodVisitor.SLEEP_JI_DESC;
import static datadog.trace.instrumentation.threadsleep.ThreadSleepCallSiteMethodVisitor.SLEEP_J_DESC;
import static datadog.trace.instrumentation.threadsleep.ThreadSleepCallSiteMethodVisitor.THREAD_INTERNAL;
import static datadog.trace.instrumentation.threadsleep.ThreadSleepCallSiteMethodVisitor.TIME_UNIT_INTERNAL;

import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.pool.TypePool;

/** Shared validation for sleep call sites seen by the scanner and rewriting visitor. */
final class ThreadSleepCallSiteMatcher {
  private static final TypeDescription THREAD = TypeDescription.ForLoadedType.of(Thread.class);

  private ThreadSleepCallSiteMatcher() {}

  static boolean isSupported(
      int opcode, String owner, String name, String descriptor, TypePool typePool) {
    if (!"sleep".equals(name)) {
      return false;
    }
    if (opcode == Opcodes.INVOKEVIRTUAL) {
      return TIME_UNIT_INTERNAL.equals(owner) && SLEEP_J_DESC.equals(descriptor);
    }
    if (opcode != Opcodes.INVOKESTATIC || !isSupportedThreadDescriptor(descriptor)) {
      return false;
    }
    if (THREAD_INTERNAL.equals(owner)) {
      return true;
    }
    if (typePool == null) {
      return false;
    }
    try {
      TypePool.Resolution resolution = typePool.describe(owner.replace('/', '.'));
      return resolution.isResolved() && resolution.resolve().isAssignableTo(THREAD);
    } catch (RuntimeException ignored) {
      return false;
    }
  }

  static boolean isPotentiallySupported(int opcode, String owner, String name, String descriptor) {
    if (!"sleep".equals(name)) {
      return false;
    }
    return (opcode == Opcodes.INVOKESTATIC && isSupportedThreadDescriptor(descriptor))
        || (opcode == Opcodes.INVOKEVIRTUAL
            && TIME_UNIT_INTERNAL.equals(owner)
            && SLEEP_J_DESC.equals(descriptor));
  }

  private static boolean isSupportedThreadDescriptor(String descriptor) {
    return SLEEP_J_DESC.equals(descriptor)
        || SLEEP_JI_DESC.equals(descriptor)
        || SLEEP_DURATION_DESC.equals(descriptor);
  }
}
