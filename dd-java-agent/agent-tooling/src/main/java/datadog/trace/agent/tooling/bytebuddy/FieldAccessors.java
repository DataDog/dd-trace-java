package datadog.trace.agent.tooling.bytebuddy;

import datadog.trace.agent.tooling.muzzle.Reference;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
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
import net.bytebuddy.pool.TypePool;
import net.bytebuddy.utility.OpenedClassReader;

/**
 * Makes the instrumented class implement an accessor interface whose methods each return one of its
 * fields, so agent code can read private fields without reflection: each getter is a plain field
 * read. Install it as type advice with {@code TypeTransformer#installFieldAccessors(Class,
 * String...)}, from an instrumentation that is {@code Instrumenter.WithStructuralChange} with the
 * accessor interface as its marker.
 *
 * <p>All or nothing: the interface and getters are added only if every named field is declared by
 * the class, is non-static and has exactly its getter's return type. Otherwise the class is left as
 * it is, and agent code sees an instance that doesn't implement the interface. Callers therefore
 * check {@code instanceof} and keep a path for classes without the accessor (a different library
 * version, or a class loaded before the agent).
 *
 * <p>The accessor interface must be visible to the instrumented class's loader (in practice, on the
 * bootstrap class path), and each of its methods must take no arguments. Getter names should be
 * prefixed with {@code $$DD$$__} so they can't collide with the class's own methods.
 *
 * <p>This class lives in agent-tooling and is public so it loads alongside agent-tooling, not in
 * the instrumentation module's own class loader: a package-private visitor nested in an
 * instrumentation can't be reached from the module across those loaders.
 */
public final class FieldAccessors implements AsmVisitorWrapper {
  private final String accessorInterface;
  private final String[] methods;
  private final String[] fields;
  private final String[] descriptors;

  /**
   * @param accessorInterface the interface to add
   * @param methodAndFieldNames pairs of getter method name (declared by the interface, no
   *     arguments) and the name of the field it returns
   */
  public FieldAccessors(Class<?> accessorInterface, String... methodAndFieldNames) {
    if (!accessorInterface.isInterface()) {
      throw new IllegalArgumentException(accessorInterface + " is not an interface");
    }
    if (methodAndFieldNames.length == 0 || methodAndFieldNames.length % 2 != 0) {
      throw new IllegalArgumentException("expected getter method / field name pairs");
    }
    int count = methodAndFieldNames.length / 2;
    this.accessorInterface = Type.getInternalName(accessorInterface);
    this.methods = new String[count];
    this.fields = new String[count];
    this.descriptors = new String[count];
    for (int i = 0; i < count; i++) {
      String method = methodAndFieldNames[2 * i];
      this.methods[i] = method;
      this.fields[i] = methodAndFieldNames[2 * i + 1];
      this.descriptors[i] = Type.getDescriptor(getter(accessorInterface, method).getReturnType());
    }
    // every abstract method gets exactly one field: the same set of names, none repeated
    Set<String> named = new HashSet<>(Arrays.asList(this.methods));
    if (named.size() != count) {
      throw new IllegalArgumentException(
          "a getter is named more than once: " + Arrays.toString(this.methods));
    }
    Set<String> declared = new HashSet<>();
    for (Method method : accessorInterface.getMethods()) {
      if (!method.isDefault() && !Modifier.isStatic(method.getModifiers())) {
        declared.add(method.getName());
      }
    }
    if (!declared.equals(named)) {
      declared.removeAll(named);
      throw new IllegalArgumentException(
          accessorInterface + " has abstract methods without a field: " + declared);
    }
  }

  private static Method getter(Class<?> accessorInterface, String name) {
    try {
      Method method = accessorInterface.getMethod(name);
      if (method.getReturnType() == void.class) {
        throw new IllegalArgumentException(method + " returns nothing");
      }
      return method;
    } catch (NoSuchMethodException e) {
      throw new IllegalArgumentException(
          accessorInterface + " has no no-argument method " + name, e);
    }
  }

  /**
   * A muzzle reference requiring {@code className} to declare every named field, non-static, with
   * its getter's return type: what {@link #appliesTo} checks, for an instrumentation's {@code
   * additionalMuzzleReferences()}. Muzzle then checks the fields against every library version in
   * the instrumentation's range, and skips the instrumentation where they're missing.
   */
  public Reference muzzleReference(String className) {
    Reference.Builder builder = new Reference.Builder(className);
    for (int i = 0; i < this.fields.length; i++) {
      builder.withField(new String[0], Reference.EXPECTS_NON_STATIC, fields[i], descriptors[i]);
    }
    return builder.build();
  }

  /**
   * Whether the accessors would be installed on {@code type}: it declares every named field,
   * non-static, with its getter's return type.
   */
  public boolean appliesTo(TypeDescription type) {
    return hasFields(type.getDeclaredFields());
  }

  private boolean hasFields(FieldList<FieldDescription.InDefinedShape> declared) {
    for (int i = 0; i < this.fields.length; i++) {
      boolean found = false;
      for (FieldDescription.InDefinedShape field : declared) {
        if (field.getName().equals(this.fields[i])) {
          found = !field.isStatic() && field.getDescriptor().equals(this.descriptors[i]);
          break;
        }
      }
      if (!found) {
        return false;
      }
    }
    return true;
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
    return hasFields(fields)
        ? new AddAccessors(classVisitor, instrumentedType.getInternalName())
        : classVisitor;
  }

  private final class AddAccessors extends ClassVisitor {
    private final String className;
    private boolean added;

    AddAccessors(ClassVisitor classVisitor, String className) {
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
      if (interfaces == null) {
        interfaces = new String[0]; // ASM passes null for a class with no interfaces
      }
      for (String existing : interfaces) {
        if (accessorInterface.equals(existing)) {
          super.visit(version, access, name, signature, superName, interfaces);
          return;
        }
      }
      String[] withAccessor = new String[interfaces.length + 1];
      System.arraycopy(interfaces, 0, withAccessor, 0, interfaces.length);
      withAccessor[interfaces.length] = accessorInterface;
      if (signature != null) {
        signature += 'L' + accessorInterface + ';';
      }
      added = true;
      super.visit(version, access, name, signature, superName, withAccessor);
    }

    @Override
    public void visitEnd() {
      if (added) {
        for (int i = 0; i < methods.length; i++) {
          MethodVisitor mv =
              // synthetic, so reflection-based tooling that lists the class's methods passes over
              // them
              cv.visitMethod(
                  Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                  methods[i],
                  "()" + descriptors[i],
                  null,
                  null);
          mv.visitCode();
          mv.visitVarInsn(Opcodes.ALOAD, 0);
          mv.visitFieldInsn(Opcodes.GETFIELD, className, fields[i], descriptors[i]);
          mv.visitInsn(Type.getType(descriptors[i]).getOpcode(Opcodes.IRETURN));
          mv.visitMaxs(Type.getType(descriptors[i]).getSize(), 1);
          mv.visitEnd();
        }
      }
      super.visitEnd();
    }
  }
}
