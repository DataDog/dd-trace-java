package datadog.trace.agent.tooling.context;

import static datadog.trace.agent.tooling.context.ShouldInjectFieldsMatcher.hasInjectedField;
import static datadog.trace.bootstrap.FieldBackedContextStores.getContextStoreId;
import static net.bytebuddy.jar.asm.Opcodes.ASM7;
import static net.bytebuddy.jar.asm.Type.getInternalName;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.FieldBackedContextAccessor;
import datadog.trace.bootstrap.FieldBackedContextStores;
import java.io.Serializable;
import java.util.Arrays;
import java.util.BitSet;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import net.bytebuddy.ClassFileVersion;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.field.FieldList;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.FieldVisitor;
import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;
import net.bytebuddy.pool.TypePool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Injects fields and accessors so the class can act as a surrogate {@link ContextStore}. */
final class FieldBackedContextInjector implements AsmVisitorWrapper {

  private static final Logger log = LoggerFactory.getLogger(FieldBackedContextInjector.class);

  static final Type OBJECT_TYPE = Type.getType(Object.class);
  static final String OBJECT_DESCRIPTOR = OBJECT_TYPE.getDescriptor();
  static final String ATOMIC_REFERENCE_FIELD_UPDATER_DESCRIPTOR =
      Type.getDescriptor(AtomicReferenceFieldUpdater.class);

  static final String FIELD_BACKED_CONTEXT_STORES_CLASS =
      getInternalName(FieldBackedContextStores.class);

  static final String FIELD_BACKED_CONTEXT_ACCESSOR_CLASS =
      getInternalName(FieldBackedContextAccessor.class);

  static final String CONTEXT_STORE_ACCESS_PREFIX = "__datadogContext$";

  static final String GETTER_METHOD = "$get$" + CONTEXT_STORE_ACCESS_PREFIX;
  static final String GETTER_METHOD_DESCRIPTOR =
      Type.getMethodDescriptor(OBJECT_TYPE, Type.INT_TYPE);

  static final String PUTTER_METHOD = "$put$" + CONTEXT_STORE_ACCESS_PREFIX;
  static final String PUTTER_METHOD_DESCRIPTOR =
      Type.getMethodDescriptor(Type.VOID_TYPE, Type.INT_TYPE, OBJECT_TYPE);

  static final String WEAK_GET_METHOD = "weakGet";
  static final String WEAK_GET_METHOD_DESCRIPTOR =
      Type.getMethodDescriptor(
          Type.getType(Object.class), Type.getType(Object.class), Type.INT_TYPE);

  static final String WEAK_PUT_METHOD = "weakPut";
  static final String WEAK_PUT_METHOD_DESCRIPTOR =
      Type.getMethodDescriptor(
          Type.VOID_TYPE, Type.getType(Object.class), Type.INT_TYPE, Type.getType(Object.class));

  static final String ATOMIC_REFERENCE_FIELD_UPDATER_NAME =
      getInternalName(AtomicReferenceFieldUpdater.class);
  static final String NEW_UPDATER_NAME = "newUpdater";
  static final String NEW_UPDATER_DESCRIPTOR =
      Type.getMethodDescriptor(
          Type.getType(AtomicReferenceFieldUpdater.class),
          Type.getType(Class.class),
          Type.getType(Class.class),
          Type.getType(String.class));

  static final String LINKAGE_ERROR_CLASS = getInternalName(LinkageError.class);

  /** Keeps track of injection requests for the class being transformed by the current thread. */
  static final ThreadLocal<BitSet> INJECTED_STORE_IDS = new ThreadLocal<>();

  final boolean serialVersionUIDFieldInjection = Config.get().isSerialVersionUIDFieldInjection();

  final String keyClassName;
  final String contextClassName;

  public FieldBackedContextInjector(final String keyClassName, final String contextClassName) {
    this.keyClassName = keyClassName;
    this.contextClassName = contextClassName;
  }

  @Override
  public int mergeWriter(final int flags) {
    return flags | ClassWriter.COMPUTE_MAXS;
  }

  @Override
  public int mergeReader(final int flags) {
    return flags;
  }

  @Override
  public ClassVisitor wrap(
      final TypeDescription instrumentedType,
      final ClassVisitor classVisitor,
      final Implementation.Context implementationContext,
      final TypePool typePool,
      final FieldList<FieldDescription.InDefinedShape> fields,
      final MethodList<?> methods,
      final int writerFlags,
      final int readerFlags) {
    return new ClassVisitor(ASM7, classVisitor) {

      private final boolean frames =
          implementationContext.getClassFileVersion().isAtLeast(ClassFileVersion.JAVA_V6);

      private String storeFieldName;
      private String storeUpdaterFieldName;

      private boolean foundField;
      private boolean foundGetter;
      private boolean foundPutter;

      private boolean foundStaticInitializer;

      private SerialVersionUIDInjector serialVersionUIDInjector;

      @Override
      public void visit(
          final int version,
          final int access,
          final String name,
          String signature,
          final String superName,
          String[] interfaces) {

        // keep track of all injection requests for the class currently being transformed
        // because we need to switch between them in the generated getter/putter methods
        int storeId = injectContextStore(keyClassName, contextClassName);
        storeFieldName = CONTEXT_STORE_ACCESS_PREFIX + storeId;
        storeUpdaterFieldName = storeFieldName + "$updater";

        if (interfaces == null) {
          interfaces = new String[] {};
        }

        if (!Arrays.asList(interfaces).contains(FIELD_BACKED_CONTEXT_ACCESSOR_CLASS)) {
          if (serialVersionUIDFieldInjection
              && instrumentedType.isAssignableTo(Serializable.class)) {
            serialVersionUIDInjector = new SerialVersionUIDInjector();
            serialVersionUIDInjector.visit(version, access, name, signature, superName, interfaces);
          }

          if (signature != null) {
            signature += 'L' + FIELD_BACKED_CONTEXT_ACCESSOR_CLASS + ';';
          }

          interfaces = Arrays.copyOf(interfaces, interfaces.length + 1);
          interfaces[interfaces.length - 1] = FIELD_BACKED_CONTEXT_ACCESSOR_CLASS;
        }

        super.visit(version, access, name, signature, superName, interfaces);
      }

      @Override
      public FieldVisitor visitField(
          final int access,
          final String name,
          final String descriptor,
          final String signature,
          final Object value) {
        if (name.startsWith(CONTEXT_STORE_ACCESS_PREFIX)) {
          if (storeFieldName.equals(name)) {
            foundField = true;
          }
        } else if (serialVersionUIDInjector != null) {
          serialVersionUIDInjector.visitField(access, name, descriptor, signature, value);
        }
        return super.visitField(access, name, descriptor, signature, value);
      }

      @Override
      public MethodVisitor visitMethod(
          final int access,
          final String name,
          final String descriptor,
          final String signature,
          final String[] exceptions) {
        MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
        if (name.equals("<clinit>")) {
          foundStaticInitializer = true;
          return new AtomicReferenceUpdaterInitializationBlock(
              mv, instrumentedType, storeFieldName, storeUpdaterFieldName);
        }
        if (name.equals(GETTER_METHOD)) {
          foundGetter = true;
        } else if (name.equals(PUTTER_METHOD)) {
          foundPutter = true;
        } else if (serialVersionUIDInjector != null) {
          serialVersionUIDInjector.visitMethod(access, name, descriptor, signature, exceptions);
        }
        return mv;
      }

      @Override
      public void visitInnerClass(
          final String name, final String outerName, final String innerName, final int access) {
        if (serialVersionUIDInjector != null) {
          serialVersionUIDInjector.visitInnerClass(name, outerName, innerName, access);
        }
        super.visitInnerClass(name, outerName, innerName, access);
      }

      @Override
      public void visitEnd() {
        if (!foundField) {
          addStoreField();
          addStoreUpdaterField();
          if (!foundStaticInitializer) {
            addStoreUpdaterInitializer();
          }
        }
        // first injector to reach here is responsible for adding the generated getter and setter
        // for the class - at this point all the other injectors will have recorded their requests
        final BitSet injectedStoreIds = getInjectedContextStores();
        if (null != injectedStoreIds) {
          if (!foundGetter || !foundPutter) {
            BitSet excludedStoreIds = new BitSet();

            // check hierarchy to see if we might need to delegate to the superclass
            boolean hasSuperStores =
                hasInjectedField(instrumentedType.getSuperClass(), excludedStoreIds);

            if (!foundGetter) {
              addStoreGetter(injectedStoreIds, hasSuperStores, excludedStoreIds);
            }
            if (!foundPutter) {
              addStorePutter(injectedStoreIds, hasSuperStores, excludedStoreIds);
            }
          }
        }

        if (serialVersionUIDInjector != null) {
          serialVersionUIDInjector.injectSerialVersionUID(instrumentedType, cv);
          serialVersionUIDInjector = null;
        }

        storeFieldName = null;
        storeUpdaterFieldName = null;

        foundField = false;
        foundGetter = false;
        foundPutter = false;
        foundStaticInitializer = false;

        super.visitEnd();
      }

      private void addStoreField() {
        cv.visitField(
            Opcodes.ACC_PRIVATE | Opcodes.ACC_TRANSIENT | Opcodes.ACC_VOLATILE,
            storeFieldName,
            OBJECT_DESCRIPTOR,
            null,
            null);
      }

      private void addStoreUpdaterField() {
        cv.visitField(
            Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_TRANSIENT,
            storeUpdaterFieldName,
            ATOMIC_REFERENCE_FIELD_UPDATER_DESCRIPTOR,
            null,
            null);
      }

      private void addStoreUpdaterInitializer() {
        MethodVisitor mv =
            new AtomicReferenceUpdaterInitializationBlock(
                cv.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null),
                instrumentedType,
                storeFieldName,
                storeUpdaterFieldName);
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
      }

      private void addStoreGetter(
          final BitSet injectedStoreIds,
          final boolean hasSuperStores,
          final BitSet excludedStoreIds) {
        final MethodVisitor mv =
            cv.visitMethod(Opcodes.ACC_PUBLIC, GETTER_METHOD, GETTER_METHOD_DESCRIPTOR, null, null);

        mv.visitCode();

        String instrumentedName = instrumentedType.getInternalName();
        boolean hasMoreStores = hasSuperStores || !excludedStoreIds.isEmpty();

        // if...else... blocks for stores injected into this class
        int injectedStoreId = injectedStoreIds.nextSetBit(0);
        while (injectedStoreId >= 0) {
          int nextStoreId = injectedStoreIds.nextSetBit(injectedStoreId + 1);

          // optimization: if we know the superclass hierarchy doesn't have any context store
          // (injected or excluded) then we can skip the id check and go straight to the field
          Label nextStoreLabel = null;
          if (hasMoreStores || nextStoreId >= 0) {
            nextStoreLabel = compareStoreId(mv, injectedStoreId);
          }

          getStoreField(mv, instrumentedName, injectedStoreId);

          if (null != nextStoreLabel) {
            beginNextStore(mv, nextStoreLabel);
          }
          injectedStoreId = nextStoreId;
        }

        // if...else... blocks for stores excluded between this class and last injected superclass
        int excludedStoreId = excludedStoreIds.nextSetBit(0);
        while (excludedStoreId >= 0) {
          int nextStoreId = excludedStoreIds.nextSetBit(excludedStoreId + 1);
          Label nextStoreLabel = compareStoreId(mv, excludedStoreId);

          invokeWeakGet(mv);

          beginNextStore(mv, nextStoreLabel);
          excludedStoreId = nextStoreId;
        }

        // else... delegate to superclass - but be prepared to fall-back to weakmap
        if (hasMoreStores) {
          Label superStoreLabel = new Label();
          Label defaultStoreLabel = new Label();

          mv.visitTryCatchBlock(
              superStoreLabel, defaultStoreLabel, defaultStoreLabel, LINKAGE_ERROR_CLASS);
          beginNextStore(mv, superStoreLabel);

          invokeSuperGet(mv, instrumentedType.getSuperClass().asErasure().getInternalName());

          mv.visitLabel(defaultStoreLabel);
          if (frames) {
            mv.visitFrame(Opcodes.F_SAME1, 0, null, 1, new Object[] {LINKAGE_ERROR_CLASS});
          }

          invokeWeakGet(mv);
        }

        mv.visitMaxs(0, 0);
        mv.visitEnd();
      }

      private void addStorePutter(
          final BitSet injectedStoreIds,
          final boolean hasSuperStores,
          final BitSet excludedStoreIds) {
        final MethodVisitor mv =
            cv.visitMethod(Opcodes.ACC_PUBLIC, PUTTER_METHOD, PUTTER_METHOD_DESCRIPTOR, null, null);

        mv.visitCode();

        String instrumentedName = instrumentedType.getInternalName();
        boolean hasMoreStores = hasSuperStores || !excludedStoreIds.isEmpty();

        // if...else... blocks for stores injected into this class
        int injectedStoreId = injectedStoreIds.nextSetBit(0);
        while (injectedStoreId >= 0) {
          int nextStoreId = injectedStoreIds.nextSetBit(injectedStoreId + 1);

          // optimization: if we know the superclass hierarchy doesn't have any context store
          // (injected or excluded) then we can skip the id check and go straight to the field
          Label nextStoreLabel = null;
          if (hasMoreStores || nextStoreId >= 0) {
            nextStoreLabel = compareStoreId(mv, injectedStoreId);
          }

          putStoreField(mv, instrumentedName, injectedStoreId);

          if (null != nextStoreLabel) {
            beginNextStore(mv, nextStoreLabel);
          }
          injectedStoreId = nextStoreId;
        }

        // if...else... blocks for stores excluded between this class and last injected superclass
        int excludedStoreId = excludedStoreIds.nextSetBit(0);
        while (excludedStoreId >= 0) {
          int nextStoreId = excludedStoreIds.nextSetBit(excludedStoreId + 1);
          Label nextStoreLabel = compareStoreId(mv, excludedStoreId);

          invokeWeakPut(mv);

          beginNextStore(mv, nextStoreLabel);
          excludedStoreId = nextStoreId;
        }

        // else... delegate to superclass - but be prepared to fall-back to weakmap
        if (hasMoreStores) {
          Label superStoreLabel = new Label();
          Label defaultStoreLabel = new Label();

          mv.visitTryCatchBlock(
              superStoreLabel, defaultStoreLabel, defaultStoreLabel, LINKAGE_ERROR_CLASS);
          beginNextStore(mv, superStoreLabel);

          invokeSuperPut(mv, instrumentedType.getSuperClass().asErasure().getInternalName());

          mv.visitLabel(defaultStoreLabel);
          if (frames) {
            mv.visitFrame(Opcodes.F_SAME1, 0, null, 1, new Object[] {LINKAGE_ERROR_CLASS});
          }

          invokeWeakPut(mv);
        }

        mv.visitMaxs(0, 0);
        mv.visitEnd();
      }

      private Label compareStoreId(final MethodVisitor mv, final int storeId) {
        mv.visitIntInsn(Opcodes.ILOAD, 1);
        Label nextStoreLabel = new Label();
        if (storeId == 0) {
          mv.visitJumpInsn(Opcodes.IFNE, nextStoreLabel);
        } else {
          if (storeId >= 0 && storeId <= 5) {
            mv.visitInsn(Opcodes.ICONST_0 + storeId);
          } else {
            mv.visitLdcInsn(storeId);
          }
          mv.visitJumpInsn(Opcodes.IF_ICMPNE, nextStoreLabel);
        }
        return nextStoreLabel;
      }

      private void beginNextStore(final MethodVisitor mv, final Label nextStoreLabel) {
        mv.visitLabel(nextStoreLabel);
        if (frames) {
          mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        }
      }

      private void getStoreField(
          final MethodVisitor mv, final String instrumentedName, final int injectedStoreId) {
        mv.visitIntInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(
            Opcodes.GETFIELD,
            instrumentedName,
            CONTEXT_STORE_ACCESS_PREFIX + injectedStoreId,
            OBJECT_DESCRIPTOR);
        mv.visitInsn(Opcodes.ARETURN);
      }

      private void putStoreField(
          final MethodVisitor mv, final String instrumentedName, final int injectedStoreId) {
        mv.visitIntInsn(Opcodes.ALOAD, 0);
        mv.visitIntInsn(Opcodes.ALOAD, 2);
        mv.visitFieldInsn(
            Opcodes.PUTFIELD,
            instrumentedName,
            CONTEXT_STORE_ACCESS_PREFIX + injectedStoreId,
            OBJECT_DESCRIPTOR);
        mv.visitInsn(Opcodes.RETURN);
      }

      private void invokeWeakGet(final MethodVisitor mv) {
        mv.visitIntInsn(Opcodes.ALOAD, 0);
        mv.visitIntInsn(Opcodes.ILOAD, 1);
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            FIELD_BACKED_CONTEXT_STORES_CLASS,
            WEAK_GET_METHOD,
            WEAK_GET_METHOD_DESCRIPTOR,
            false);
        mv.visitInsn(Opcodes.ARETURN);
      }

      private void invokeWeakPut(final MethodVisitor mv) {
        mv.visitIntInsn(Opcodes.ALOAD, 0);
        mv.visitIntInsn(Opcodes.ILOAD, 1);
        mv.visitIntInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            FIELD_BACKED_CONTEXT_STORES_CLASS,
            WEAK_PUT_METHOD,
            WEAK_PUT_METHOD_DESCRIPTOR,
            false);
        mv.visitInsn(Opcodes.RETURN);
      }

      private void invokeSuperGet(final MethodVisitor mv, final String superName) {
        mv.visitIntInsn(Opcodes.ALOAD, 0);
        mv.visitIntInsn(Opcodes.ILOAD, 1);
        mv.visitMethodInsn(
            Opcodes.INVOKESPECIAL, superName, GETTER_METHOD, GETTER_METHOD_DESCRIPTOR, false);
        mv.visitInsn(Opcodes.ARETURN);
      }

      private void invokeSuperPut(final MethodVisitor mv, final String superName) {
        mv.visitIntInsn(Opcodes.ALOAD, 0);
        mv.visitIntInsn(Opcodes.ILOAD, 1);
        mv.visitIntInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(
            Opcodes.INVOKESPECIAL, superName, PUTTER_METHOD, PUTTER_METHOD_DESCRIPTOR, false);
        mv.visitInsn(Opcodes.RETURN);
      }
    };
  }

  /** Requests injection of a context store for a key and context. */
  static int injectContextStore(final String keyClassName, final String contextClassName) {
    int storeId = getContextStoreId(keyClassName, contextClassName);

    BitSet injectedStoreIds = INJECTED_STORE_IDS.get();
    if (null == injectedStoreIds) {
      injectedStoreIds = new BitSet();
      INJECTED_STORE_IDS.set(injectedStoreIds);
    }
    injectedStoreIds.set(storeId);

    return storeId;
  }

  /** Returns all context store injection requests for the class being transformed. */
  static BitSet getInjectedContextStores() {
    BitSet injectedStoreIds = INJECTED_STORE_IDS.get();
    if (null != injectedStoreIds) {
      INJECTED_STORE_IDS.remove();
    }
    return injectedStoreIds;
  }

  private static final class SerialVersionUIDInjector
      extends datadog.trace.agent.tooling.context.asm.SerialVersionUIDAdder {
    public SerialVersionUIDInjector() {
      super(ASM7, null);
    }

    public void injectSerialVersionUID(
        final TypeDescription instrumentedType, final ClassVisitor transformer) {
      if (!hasSVUID()) {
        try {
          transformer.visitField(
              Opcodes.ACC_FINAL | Opcodes.ACC_STATIC,
              "serialVersionUID",
              "J",
              null,
              computeSVUID());
        } catch (final Exception e) {
          log.debug("Failed to add serialVersionUID to {}", instrumentedType.getActualName(), e);
        }
      }
    }
  }

  private static class AtomicReferenceUpdaterInitializationBlock extends MethodVisitor {
    private final TypeDescription instrumentedType;
    private final String storeFieldName;
    private final String storeUpdaterFieldName;

    public AtomicReferenceUpdaterInitializationBlock(
        MethodVisitor mv,
        TypeDescription instrumentedType,
        String storeFieldName,
        String storeUpdaterFieldName) {
      super(Opcodes.ASM7, mv);
      this.instrumentedType = instrumentedType;
      this.storeFieldName = storeFieldName;
      this.storeUpdaterFieldName = storeUpdaterFieldName;
    }

    @Override
    public void visitCode() {
      super.visitCode();
      /*
       static {};
       Code:
          0: ldc           #4                  // class Foo
          2: ldc           #5                  // class java/lang/Object
          4: ldc           #6                  // String __datadogContext$0
          6: invokestatic  #7                  // Method java/util/concurrent/atomic/AtomicReferenceFieldUpdater.newUpdater:(Ljava/lang/Class;Ljava/lang/Class;Ljava/lang/String;)Ljava/util/concurrent/atomic/AtomicReferenceFieldUpdater;
          9: putstatic     #2                  // Field __datadogContext$0$updater:Ljava/util/concurrent/atomic/AtomicReferenceFieldUpdater;
      */
      Type targetType = Type.getObjectType(instrumentedType.getInternalName());
      // 3 stack slots, no locals (see visitMaxs)
      super.visitLdcInsn(targetType);
      super.visitLdcInsn(OBJECT_TYPE);
      super.visitLdcInsn(storeFieldName);
      super.visitMethodInsn(
          Opcodes.INVOKESTATIC,
          ATOMIC_REFERENCE_FIELD_UPDATER_NAME,
          NEW_UPDATER_NAME,
          NEW_UPDATER_DESCRIPTOR,
          false);
      super.visitFieldInsn(
          Opcodes.PUTSTATIC,
          "java/util/concurrent/atomic/AtomicReferenceFieldUpdater",
          storeUpdaterFieldName,
          targetType.getDescriptor());
    }

    @Override
    public void visitMaxs(int maxStack, int maxLocals) {
      super.visitMaxs(Math.max(maxStack, 3), maxLocals);
    }
  }
}
