package datadog.trace.agent.tooling.bytebuddy;

import datadog.trace.agent.tooling.context.asm.SerialVersionUIDInjector;
import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.bootstrap.instrumentation.api.Field;
import java.io.Serializable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
import net.bytebuddy.jar.asm.Type;
import net.bytebuddy.pool.TypePool;
import net.bytebuddy.utility.OpenedClassReader;

/**
 * Makes the instrumented class implement an accessor interface whose methods each return one of its
 * fields, so agent code can read private fields without reflection: each getter is a plain field
 * read. Install it as type advice with {@code TypeTransformer#installFieldAccessors(Class)}, from
 * an instrumentation that is {@code Instrumenter.WithStructuralChange} with the accessor interface
 * as its marker.
 *
 * <p>All or nothing: the interface and getters are added only if every named field is declared by
 * the class, is non-static and has exactly its getter's return type. Otherwise the class is left as
 * it is, and agent code sees an instance that doesn't implement the interface. Callers therefore
 * check {@code instanceof} and keep a path for classes without the accessor: classes loaded before
 * the agent, types matched by hierarchy, or a known type in a {@code ForKnownTypes} list that lacks
 * the fields.
 *
 * <p>Muzzle: for a {@code ForSingleType} or {@code ForKnownTypes} instrumentation, the muzzle
 * generator turns the fields into references of the <em>whole module</em>, so a library version
 * without them disables all of the module's instrumentation. Install accessors from a module of
 * their own.
 *
 * <p>The accessor interface can be a class of the instrumentation module: installing the accessors
 * also injects it, as a helper, into the instrumented class's loader, ahead of the class itself. An
 * interface on the bootstrap class path is used as it is. Agent code that reads the accessors must
 * see the same interface, so its helpers must be injected into the same loader (and declare the
 * interface as a helper, or use {@code injectHelperDependencies()}). Each of the interface's
 * abstract methods is a getter taking no arguments: {@code $$DD$$__foo()} returns field {@code
 * foo}, and a {@link Field} annotation names a field the getter's name doesn't. The {@code
 * $$DD$$__} prefix also keeps getters from colliding with the class's own methods.
 *
 * <p>Adding an interface and methods changes a {@code Serializable} class's default {@code
 * serialVersionUID}, so a class that declares none gets its original value pinned as a field, as
 * the field-backed context stores do (unless {@code serialVersionUID} field injection is disabled).
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

  /** The getter-name prefix that names a getter's field by convention. */
  public static final String GETTER_PREFIX = "$$DD$$__";

  /**
   * @param accessorInterface the interface to add: each abstract method is a getter taking no
   *     arguments, returning the field named by its {@link Field} annotation or, without one, by
   *     its name after the {@link #GETTER_PREFIX}
   */
  public FieldAccessors(Class<?> accessorInterface) {
    if (!accessorInterface.isInterface()) {
      throw new IllegalArgumentException(accessorInterface + " is not an interface");
    }
    this.accessorInterface = Type.getInternalName(accessorInterface);
    List<Method> getters = new ArrayList<>();
    for (Method method : accessorInterface.getMethods()) {
      if (method.isDefault() || Modifier.isStatic(method.getModifiers())) {
        continue;
      }
      // only a no-argument getter is generated, so a method taking arguments would be left
      // unimplemented and throw AbstractMethodError when called (a covariant redeclaration is
      // fine: javac bridges it with a default method)
      if (method.getParameterCount() != 0) {
        throw new IllegalArgumentException(method + " takes arguments");
      }
      if (method.getReturnType() == void.class) {
        throw new IllegalArgumentException(method + " returns nothing");
      }
      getters.add(method);
    }
    if (getters.isEmpty()) {
      throw new IllegalArgumentException(accessorInterface + " declares no getters");
    }
    // a stable order, so the generated getters and muzzle references don't depend on reflection
    getters.sort(Comparator.comparing(Method::getName));
    int count = getters.size();
    this.methods = new String[count];
    this.fields = new String[count];
    this.descriptors = new String[count];
    for (int i = 0; i < count; i++) {
      Method getter = getters.get(i);
      if (i > 0 && getter.getName().equals(this.methods[i - 1])) {
        // two abstract declarations of one name: only one could be generated
        throw new IllegalArgumentException(getter + " is declared more than once");
      }
      this.methods[i] = getter.getName();
      this.fields[i] = fieldName(getter);
      this.descriptors[i] = Type.getDescriptor(getter.getReturnType());
    }
  }

  private static String fieldName(Method getter) {
    Field field = getter.getAnnotation(Field.class);
    if (field != null) {
      return field.value();
    }
    String name = getter.getName();
    if (name.startsWith(GETTER_PREFIX) && name.length() > GETTER_PREFIX.length()) {
      return name.substring(GETTER_PREFIX.length());
    }
    throw new IllegalArgumentException(
        getter + " needs @Field, or a name of " + GETTER_PREFIX + " and the field");
  }

  /** The accessor interface's binary name. */
  public String accessorInterfaceName() {
    return accessorInterface.replace('/', '.');
  }

  /**
   * A muzzle reference requiring {@code className} to have every named field, non-static, with its
   * getter's return type. The muzzle generator adds it for each {@code installFieldAccessors} call,
   * so muzzle checks the fields against every library version in the instrumentation's range, and
   * skips the instrumentation where they're missing. Muzzle also accepts a field the class
   * inherits, which {@link #appliesTo} doesn't: a version that moved a field to a superclass passes
   * muzzle, and the class is then left without the accessor.
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
    return hasFields(fields) ? new AddAccessors(classVisitor, instrumentedType) : classVisitor;
  }

  private final class AddAccessors extends ClassVisitor {
    private final TypeDescription instrumentedType;
    private final String className;
    private boolean added;
    // fed the class's original structure, to pin its default serialVersionUID (null if not needed)
    private SerialVersionUIDInjector serialVersionUIDInjector;

    AddAccessors(ClassVisitor classVisitor, TypeDescription instrumentedType) {
      super(OpenedClassReader.ASM_API, classVisitor);
      this.instrumentedType = instrumentedType;
      this.className = instrumentedType.getInternalName();
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
      if (InstrumenterConfig.get().isSerialVersionUIDFieldInjection()
          && instrumentedType.isAssignableTo(Serializable.class)) {
        serialVersionUIDInjector = new SerialVersionUIDInjector();
        serialVersionUIDInjector.visit(version, access, name, signature, superName, interfaces);
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
    public FieldVisitor visitField(
        int access, String name, String descriptor, String signature, Object value) {
      if (serialVersionUIDInjector != null) {
        serialVersionUIDInjector.visitField(access, name, descriptor, signature, value);
      }
      return super.visitField(access, name, descriptor, signature, value);
    }

    @Override
    public MethodVisitor visitMethod(
        int access, String name, String descriptor, String signature, String[] exceptions) {
      if (serialVersionUIDInjector != null) {
        serialVersionUIDInjector.visitMethod(access, name, descriptor, signature, exceptions);
      }
      return super.visitMethod(access, name, descriptor, signature, exceptions);
    }

    @Override
    public void visitInnerClass(String name, String outerName, String innerName, int access) {
      if (serialVersionUIDInjector != null) {
        serialVersionUIDInjector.visitInnerClass(name, outerName, innerName, access);
      }
      super.visitInnerClass(name, outerName, innerName, access);
    }

    @Override
    public void visitEnd() {
      if (added) {
        for (int i = 0; i < methods.length; i++) {
          // synthetic, so tooling that filters synthetic members passes over them
          MethodVisitor mv =
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
        if (serialVersionUIDInjector != null) {
          serialVersionUIDInjector.injectSerialVersionUID(instrumentedType, cv);
          serialVersionUIDInjector = null;
        }
      }
      super.visitEnd();
    }
  }
}
