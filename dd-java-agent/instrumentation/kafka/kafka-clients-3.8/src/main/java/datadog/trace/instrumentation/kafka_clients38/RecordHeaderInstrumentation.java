package datadog.trace.instrumentation.kafka_clients38;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.BufferedHeaderFields;
import java.nio.ByteBuffer;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.field.FieldList;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.pool.TypePool;
import net.bytebuddy.utility.OpenedClassReader;

/**
 * Makes Kafka's {@code RecordHeader} implement {@link BufferedHeaderFields}, so extraction can read
 * a fetched header's key and value from the record batch without {@code key()} decoding and {@code
 * value()} copying them. A {@code RecordHeader} loaded before the agent, or with other fields, is
 * left alone, and extraction reads it through {@code key()} and {@code value()}.
 */
@AutoService(InstrumenterModule.class)
public final class RecordHeaderInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.WithStructuralChange {

  static final String RECORD_HEADER = "org.apache.kafka.common.header.internals.RecordHeader";

  /** The getter each field gets, in {@link BufferedHeaderFields}: {method, field, field type}. */
  private static final Object[][] GETTERS = {
    {"$$DD$$__key", "key", String.class},
    {"$$DD$$__keyBuffer", "keyBuffer", ByteBuffer.class},
    {"$$DD$$__value", "value", byte[].class},
    {"$$DD$$__valueBuffer", "valueBuffer", ByteBuffer.class},
  };

  public RecordHeaderInstrumentation() {
    super("kafka", "kafka-3.8");
  }

  @Override
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
    return hasClassNamed("org.apache.kafka.clients.MetadataRecoveryStrategy"); // since 3.8
  }

  @Override
  public String instrumentedType() {
    return RECORD_HEADER;
  }

  @Override
  public Class<?> structuralChangeMarker() {
    return BufferedHeaderFields.class;
  }

  @Override
  public void typeAdvice(TypeTransformer transformer) {
    transformer.applyAdvice(new FieldGetters());
  }

  /** Whether {@code fields} has the four fields the getters read, with the types they return. */
  static boolean hasExpectedFields(FieldList<FieldDescription.InDefinedShape> fields) {
    for (Object[] getter : GETTERS) {
      FieldList<FieldDescription.InDefinedShape> field = fields.filter(named((String) getter[1]));
      if (field.size() != 1
          || field.getOnly().isStatic()
          || !field.getOnly().getType().asErasure().represents((Class<?>) getter[2])) {
        return false;
      }
    }
    return true;
  }

  /**
   * Adds {@link BufferedHeaderFields} and its getters to the class, if it has the fields they read.
   * Otherwise the class is left as it is.
   */
  static final class FieldGetters implements AsmVisitorWrapper {
    @Override
    public int mergeWriter(int flags) {
      return flags;
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
      return hasExpectedFields(fields)
          ? new AddGetters(classVisitor, instrumentedType.getInternalName())
          : classVisitor;
    }
  }

  private static final class AddGetters extends ClassVisitor {
    private static final String INTERFACE = Type.getInternalName(BufferedHeaderFields.class);

    private final String className;
    private boolean added;

    AddGetters(ClassVisitor classVisitor, String className) {
      super(OpenedClassReader.ASM_API, classVisitor);
      this.className = className;
    }

    @Override
    public void visit(
        int version,
        int access,
        String name,
        String signature,
        String superName,
        String[] interfaces) {
      for (String existing : interfaces) {
        if (INTERFACE.equals(existing)) {
          super.visit(version, access, name, signature, superName, interfaces);
          return;
        }
      }
      String[] withAccessor = new String[interfaces.length + 1];
      System.arraycopy(interfaces, 0, withAccessor, 0, interfaces.length);
      withAccessor[interfaces.length] = INTERFACE;
      if (signature != null) {
        signature += 'L' + INTERFACE + ';';
      }
      added = true;
      super.visit(version, access, name, signature, superName, withAccessor);
    }

    @Override
    public void visitEnd() {
      if (added) {
        for (Object[] getter : GETTERS) {
          String descriptor = Type.getDescriptor((Class<?>) getter[2]);
          MethodVisitor mv =
              cv.visitMethod(Opcodes.ACC_PUBLIC, (String) getter[0], "()" + descriptor, null, null);
          mv.visitCode();
          mv.visitVarInsn(Opcodes.ALOAD, 0);
          mv.visitFieldInsn(Opcodes.GETFIELD, className, (String) getter[1], descriptor);
          mv.visitInsn(Opcodes.ARETURN);
          mv.visitMaxs(1, 1);
          mv.visitEnd();
        }
      }
      super.visitEnd();
    }
  }
}
