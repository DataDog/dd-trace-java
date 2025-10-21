package datadog.trace.agent.tooling.bytebuddy.profiling;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.field.FieldList;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.FieldVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.pool.TypePool;
import net.bytebuddy.utility.OpenedClassReader;

public class UnwrappingVisitor implements AsmVisitorWrapper {

  private final Map<String, String> classNameToDelegateFieldNames;

  public UnwrappingVisitor(String... classAndDelegateFieldNames) {
    assert classAndDelegateFieldNames.length % 2 == 0;
    classNameToDelegateFieldNames = new HashMap<>(classAndDelegateFieldNames.length);
    for (int i = 0; i < classAndDelegateFieldNames.length; i += 2) {
      classNameToDelegateFieldNames.put(
          classAndDelegateFieldNames[i], classAndDelegateFieldNames[i + 1]);
    }
  }

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
    String fieldName = classNameToDelegateFieldNames.get(instrumentedType.getName());
    return fieldName == null
        ? classVisitor
        : new ImplementTaskWrapperClassVisitor(
            classVisitor, instrumentedType.getInternalName(), fieldName);
  }

  static class ImplementTaskWrapperClassVisitor extends ClassVisitor {

    private static final String TASK_WRAPPER =
        "datadog/trace/bootstrap/instrumentation/api/TaskWrapper";

    private final String className;
    private final String fieldName;
    private boolean modify = false;
    private String descriptor;

    protected ImplementTaskWrapperClassVisitor(
        ClassVisitor classVisitor, String className, String fieldName) {
      super(OpenedClassReader.ASM_API, classVisitor);
      this.className = className;
      this.fieldName = fieldName;
    }

    @Override
    public void visit(
        int version,
        int access,
        String name,
        String signature,
        String superName,
        String[] interfaces) {
      if (interfaces == null || !Arrays.asList(interfaces).contains(TASK_WRAPPER)) {
        interfaces = append(interfaces, TASK_WRAPPER);
        if (signature != null) {
          signature += 'L' + TASK_WRAPPER + ';';
        }
        modify = true;
      }
      super.visit(version, access, name, signature, superName, interfaces);
    }

    private static String[] append(String[] strings, String toAppend) {
      if (strings == null || strings.length == 0) {
        return new String[] {toAppend};
      }
      String[] appended = Arrays.copyOf(strings, strings.length + 1);
      appended[strings.length] = toAppend;
      return appended;
    }

    @Override
    public FieldVisitor visitField(
        int access, String name, String descriptor, String signature, Object value) {
      if (fieldName.equals(name)) {
        this.descriptor = descriptor;
      }
      return super.visitField(access, name, descriptor, signature, value);
    }

    @Override
    public void visitEnd() {
      if (modify) {
        addUnwrap();
      }
    }

    private void addUnwrap() {
      MethodVisitor mv =
          cv.visitMethod(Opcodes.ACC_PUBLIC, "$$DD$$__unwrap", "()Ljava/lang/Object;", null, null);
      mv.visitCode();
      if (descriptor != null) {
        // we found the field so can return it
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, className, fieldName, descriptor);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(1, 1);
      } else {
        // we've added the interface but haven't found the field we wanted to unwrap,
        // so we have to generate the method, so just return null
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(1, 1);
      }
      mv.visitEnd();
    }
  }
}
