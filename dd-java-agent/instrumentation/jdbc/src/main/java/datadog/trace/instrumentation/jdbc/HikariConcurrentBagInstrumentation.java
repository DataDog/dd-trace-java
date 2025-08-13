package datadog.trace.instrumentation.jdbc;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.api.Tags.DB_POOL_NAME;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import com.google.auto.service.AutoService;
import com.zaxxer.hikari.pool.HikariPool;
import com.zaxxer.hikari.util.ConcurrentBag;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.field.FieldList;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;
import net.bytebuddy.pool.TypePool;

/**
 * Instrument Hikari's ConcurrentBag class to detect when blocking occurs trying to get an entry
 * from the connection pool.
 */
@AutoService(InstrumenterModule.class)
public final class HikariConcurrentBagInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType,
        Instrumenter.HasTypeAdvice,
        Instrumenter.HasMethodAdvice {
  private static final String INSTRUMENTATION_NAME = "hikari";
  private static final String POOL_WAITING = "pool.waiting";

  public HikariConcurrentBagInstrumentation() {
    super("jdbc-datasource");
  }

  @Override
  public String instrumentedType() {
    return "com.zaxxer.hikari.util.ConcurrentBag";
  }

  @Override
  public Map<String, String> contextStore() {
    // For getting the poolName
    return singletonMap("com.zaxxer.hikari.util.ConcurrentBag", String.class.getName());
  }

  @Override
  public void typeAdvice(TypeTransformer transformer) {
    transformer.applyAdvice(new ConcurrentBagVisitorWrapper());
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isConstructor(), HikariConcurrentBagInstrumentation.class.getName() + "$ConstructorAdvice");
    transformer.applyAdvice(
        named("borrow"), HikariConcurrentBagInstrumentation.class.getName() + "$BorrowAdvice");
  }

  public static class ConstructorAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    static void after(
        @Advice.This ConcurrentBag<?> thiz,
        @Advice.FieldValue("listener") ConcurrentBag.IBagStateListener listener)
        throws IllegalAccessException, NoSuchFieldException {
      HikariPool hikariPool = (HikariPool) listener;

      /*
       * In earlier versions of Hikari, poolName is directly inside HikariPool, and
       * in later versions it is in the PoolBase superclass.
       */
      final Class<?> hikariPoolSuper = hikariPool.getClass().getSuperclass();
      final Class<?> poolNameContainingClass;
      if (!hikariPoolSuper.getName().equals("java.lang.Object")) {
        poolNameContainingClass = hikariPoolSuper;
      } else {
        poolNameContainingClass = hikariPool.getClass();
      }
      Field poolNameField = poolNameContainingClass.getDeclaredField("poolName");
      poolNameField.setAccessible(true);
      String poolName = (String) poolNameField.get(hikariPool);
      InstrumentationContext.get(ConcurrentBag.class, String.class).put(thiz, poolName);
    }
  }

  public static class BorrowAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static Long onEnter() {
      HikariWaitingTracker.clearWaiting();
      return System.currentTimeMillis();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.This ConcurrentBag thiz,
        @Advice.Enter final Long startTimeMillis,
        @Advice.Thrown final Throwable throwable) {
      if (HikariWaitingTracker.wasWaiting()) {
        final AgentSpan span =
            startSpan(
                INSTRUMENTATION_NAME,
                POOL_WAITING,
                TimeUnit.MILLISECONDS.toMicros(startTimeMillis));
        final String poolName =
            InstrumentationContext.get(ConcurrentBag.class, String.class).get(thiz);
        if (poolName != null) {
          span.setTag(DB_POOL_NAME, poolName);
        }
        // XXX should we do anything with the throwable?
        span.finish();
      }
      HikariWaitingTracker.clearWaiting();
    }
  }

  private class ConcurrentBagVisitorWrapper implements AsmVisitorWrapper {
    @Override
    public int mergeWriter(int flags) {
      return flags | ClassWriter.COMPUTE_MAXS;
    }

    @Override
    public int mergeReader(int flags) {
      return flags;
    }

    @Override
    public ClassVisitor wrap(
        TypeDescription instrumentedType,
        ClassVisitor classVisitor,
        Implementation.Context implementationContext,
        TypePool typePool,
        FieldList<FieldDescription.InDefinedShape> fields,
        MethodList<?> methods,
        int writerFlags,
        int readerFlags) {
      return new ConcurrentBagClassVisitor(Opcodes.ASM8, classVisitor);
    }
  }

  public static class ConcurrentBagClassVisitor extends ClassVisitor {
    public ConcurrentBagClassVisitor(int api, ClassVisitor cv) {
      super(api, cv);
    }

    @Override
    public MethodVisitor visitMethod(
        int access, String name, String descriptor, String signature, String[] exceptions) {
      MethodVisitor superMv = super.visitMethod(access, name, descriptor, signature, exceptions);
      if ("borrow".equals(name)
          && "(JLjava/util/concurrent/TimeUnit;)Lcom/zaxxer/hikari/util/ConcurrentBag$IConcurrentBagEntry;"
              .equals(descriptor)) {
        return new BorrowMethodVisitor(api, superMv);
      } else {
        return superMv;
      }
    }
  }

  public static class BorrowMethodVisitor extends MethodVisitor {
    public BorrowMethodVisitor(int api, MethodVisitor superMv) {
      super(api, superMv);
    }


    /**
     * Adds a call to HikariWaitingTracker.setWaiting whenever Hikari is blocking waiting on a connection from the pool
     * to be available whenever either of these method calls happen (which one depends on Hikari version):
     * <br/>
     * <code>synchronizer.waitUntilSequenceExceeded(startSeq, timeout)</code>
     * -- <a href="     https://github.com/brettwooldridge/HikariCP/blob/5adf46c148dfa095886c7c754f365b0644dc04cb/src/main/java/com/zaxxer/hikari/util/ConcurrentBag.java#L159">prior to 2.6.0</a>
     * <br/>
     * <code>handoffQueue.poll(timeout, NANOSECONDS)</code>
     * -- <a href="https://github.com/brettwooldridge/HikariCP/blob/22cc9bde6c0fb54c8ac009122a20d2f579e1a54a/src/main/java/com/zaxxer/hikari/util/ConcurrentBag.java#L162">2.6.0 and later</a>
     */
    @Override
    public void visitMethodInsn(
        int opcode, String owner, String name, String descriptor, boolean isInterface) {
      if ((opcode == Opcodes.INVOKEVIRTUAL
              && owner.equals("com/zaxxer/hikari/util/QueuedSequenceSynchronizer")
              && name.equals("waitUntilSequenceExceeded")
              && descriptor.equals("(JJ)Z"))
          || (opcode == Opcodes.INVOKEVIRTUAL
              && owner.equals("java/util/concurrent/SynchronousQueue")
              && name.equals("poll")
              && descriptor.equals("(JLjava/util/concurrent/TimeUnit;)Ljava/lang/Object;"))) {
        super.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            Type.getInternalName(HikariWaitingTracker.class),
            "setWaiting",
            "()V",
            false);
        // original stack
      }
      super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
    }
  }

  public static class HikariWaitingTracker {
    private static final ThreadLocal<Boolean> tracker = ThreadLocal.withInitial(() -> false);

    public static void clearWaiting() {
      tracker.set(false);
    }

    public static void setWaiting() {
      tracker.set(true);
    }

    public static boolean wasWaiting() {
      return tracker.get();
    }
  }
}
