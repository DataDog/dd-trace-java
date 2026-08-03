// Copyright 2026 Datadog, Inc.
package datadog.trace.instrumentation.threadsleep;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.tooling.bytebuddy.csi.Advices;
import datadog.trace.agent.tooling.bytebuddy.csi.CallSiteTransformer;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.utility.JavaModule;
import net.bytebuddy.utility.OpenedClassReader;
import org.junit.jupiter.api.Test;

class ThreadSleepCallSiteTest {
  private static final String TASK_BLOCK_HELPER =
      "datadog/trace/bootstrap/instrumentation/java/concurrent/TaskBlockHelper";

  @Test
  void registersEverySupportedExactCallSite() {
    Advices advices = ThreadSleepProfilingInstrumentation.createAdvices();

    assertNotNull(advices.findAdvice("java/lang/Thread", "sleep", "(J)V"));
    assertNotNull(advices.findAdvice("java/lang/Thread", "sleep", "(JI)V"));
    assertNotNull(advices.findAdvice("java/lang/Thread", "sleep", "(Ljava/time/Duration;)V"));
    assertNotNull(advices.findAdvice("java/util/concurrent/TimeUnit", "sleep", "(J)V"));
  }

  @Test
  void exactThreadSleepOverloadsAreReplacedByStackCompatibleHelpers() {
    byte[] transformed = transform(ExactThreadSleepFixture.class);

    InvocationCounts counts = scan(transformed);
    assertEquals(2, counts.helperCalls);
    assertEquals(0, counts.threadSleepCalls);
    assertEquals(0, counts.timeUnitSleepCalls);
  }

  @Test
  void timeUnitSleepIsReplacedByStaticHelper() {
    byte[] transformed = transform(TimeUnitSleepFixture.class);

    InvocationCounts counts = scan(transformed);
    assertEquals(1, counts.helperCalls);
    assertEquals(0, counts.timeUnitSleepCalls);
  }

  @Test
  void durationSleepIsReplacedAndExecutesWhenAvailable() throws Exception {
    byte[] bytecode = durationFixture();
    Class<?> original = new ByteArrayClassLoader(getClass().getClassLoader()).define(bytecode);
    byte[] transformed = transform(original, bytecode);

    InvocationCounts counts = scan(transformed);
    assertEquals(1, counts.helperCalls);
    assertEquals(0, counts.threadSleepCalls);

    if (hasDurationSleep()) {
      Class<?> fixture = new ByteArrayClassLoader(getClass().getClassLoader()).define(transformed);
      Method sleep = fixture.getDeclaredMethod("sleep", Duration.class);
      sleep.invoke(null, Duration.ZERO);
    }
  }

  @Test
  void sameMethodCatchStillReceivesInterruptedException() throws Exception {
    byte[] transformed = transform(CaughtInterruptFixture.class);
    Class<?> fixture = new ByteArrayClassLoader(getClass().getClassLoader()).define(transformed);
    Method catchesInterrupt = fixture.getDeclaredMethod("catchesInterrupt");
    catchesInterrupt.setAccessible(true);

    Thread.currentThread().interrupt();
    try {
      assertTrue((Boolean) catchesInterrupt.invoke(null));
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void threadSubclassSyntaxIsNotMistakenForExactThreadSleep() {
    byte[] transformed = transform(ThreadSubclassSleepFixture.class);

    InvocationCounts counts = scan(transformed);
    assertEquals(0, counts.helperCalls);
    assertEquals(1, counts.subclassSleepCalls);
  }

  @Test
  void hiddenThreadSubclassMethodIsNotInstrumented() {
    byte[] transformed = transform(HiddenThreadSleepFixture.class);

    InvocationCounts counts = scan(transformed);
    assertEquals(0, counts.helperCalls);
    assertEquals(1, counts.hiddenSleepCalls);
  }

  private static byte[] transform(Class<?> fixture) {
    return transform(fixture, null);
  }

  private static byte[] transform(Class<?> fixture, byte[] bytecode) {
    TypeDescription type = TypeDescription.ForLoadedType.of(fixture);
    DynamicType.Builder<?> builder =
        bytecode == null
            ? new ByteBuddy().redefine(fixture)
            : new ByteBuddy()
                .redefine(fixture, ClassFileLocator.Simple.of(fixture.getName(), bytecode));
    CallSiteTransformer transformer =
        new CallSiteTransformer(
            "thread-sleep-test", ThreadSleepProfilingInstrumentation.createAdvices());
    builder =
        transformer.transform(
            builder,
            type,
            fixture.getClassLoader(),
            JavaModule.ofType(fixture),
            fixture.getProtectionDomain());
    return builder.make().getBytes();
  }

  private static InvocationCounts scan(byte[] bytecode) {
    InvocationCounts counts = new InvocationCounts();
    new ClassReader(bytecode)
        .accept(
            new ClassVisitor(OpenedClassReader.ASM_API) {
              @Override
              public MethodVisitor visitMethod(
                  int access,
                  String name,
                  String descriptor,
                  String signature,
                  String[] exceptions) {
                return new MethodVisitor(OpenedClassReader.ASM_API) {
                  @Override
                  public void visitMethodInsn(
                      int opcode,
                      String owner,
                      String name,
                      String descriptor,
                      boolean isInterface) {
                    if (opcode == Opcodes.INVOKESTATIC && TASK_BLOCK_HELPER.equals(owner)) {
                      counts.helperCalls++;
                    } else if ("java/lang/Thread".equals(owner) && "sleep".equals(name)) {
                      counts.threadSleepCalls++;
                    } else if ("java/util/concurrent/TimeUnit".equals(owner)
                        && "sleep".equals(name)) {
                      counts.timeUnitSleepCalls++;
                    } else if (owner.endsWith("$ThreadSubclass") && "sleep".equals(name)) {
                      counts.subclassSleepCalls++;
                    } else if (owner.endsWith("$HiddenThread") && "sleep".equals(name)) {
                      counts.hiddenSleepCalls++;
                    }
                  }
                };
              }
            },
            ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    return counts;
  }

  private static boolean hasDurationSleep() {
    try {
      Thread.class.getMethod("sleep", Duration.class);
      return true;
    } catch (NoSuchMethodException ignored) {
      return false;
    }
  }

  private static byte[] durationFixture() {
    ClassWriter writer = new ClassWriter(0);
    writer.visit(
        Opcodes.V1_8,
        Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
        "datadog/trace/instrumentation/threadsleep/GeneratedDurationSleepFixture",
        null,
        "java/lang/Object",
        null);
    MethodVisitor method =
        writer.visitMethod(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "sleep",
            "(Ljava/time/Duration;)V",
            null,
            new String[] {"java/lang/InterruptedException"});
    method.visitCode();
    method.visitVarInsn(Opcodes.ALOAD, 0);
    method.visitMethodInsn(
        Opcodes.INVOKESTATIC, "java/lang/Thread", "sleep", "(Ljava/time/Duration;)V", false);
    method.visitInsn(Opcodes.RETURN);
    method.visitMaxs(1, 1);
    method.visitEnd();
    writer.visitEnd();
    return writer.toByteArray();
  }

  static final class ExactThreadSleepFixture {
    static void sleep(long millis, int nanos) throws InterruptedException {
      Thread.sleep(millis);
      Thread.sleep(millis, nanos);
    }
  }

  static final class TimeUnitSleepFixture {
    static void sleep(long timeout) throws InterruptedException {
      TimeUnit.MILLISECONDS.sleep(timeout);
    }
  }

  static final class CaughtInterruptFixture {
    static boolean catchesInterrupt() {
      try {
        Thread.sleep(1_000L);
        return false;
      } catch (InterruptedException expected) {
        return true;
      }
    }
  }

  static class ThreadSubclass extends Thread {}

  static final class ThreadSubclassSleepFixture {
    static void sleep(long timeout) throws InterruptedException {
      ThreadSubclass.sleep(timeout);
    }
  }

  static final class HiddenThread extends Thread {
    public static void sleep(long ignored) {}
  }

  static final class HiddenThreadSleepFixture {
    static void sleep(long timeout) {
      HiddenThread.sleep(timeout);
    }
  }

  private static final class InvocationCounts {
    private int helperCalls;
    private int threadSleepCalls;
    private int timeUnitSleepCalls;
    private int subclassSleepCalls;
    private int hiddenSleepCalls;
  }

  private static final class ByteArrayClassLoader extends ClassLoader {
    private ByteArrayClassLoader(ClassLoader parent) {
      super(parent);
    }

    private Class<?> define(byte[] bytecode) {
      return defineClass(null, bytecode, 0, bytecode.length);
    }
  }
}
