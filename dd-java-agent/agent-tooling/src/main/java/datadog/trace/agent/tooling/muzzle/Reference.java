package datadog.trace.agent.tooling.muzzle;

import static java.util.Arrays.asList;

import datadog.config.util.Strings;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Array;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;

/** An immutable reference to a jvm class. */
public class Reference {
  public final String[] sources;
  public final int flags;
  public final String className;
  public final String superName;
  public final String[] interfaces;
  public final Field[] fields;
  public final Method[] methods;

  public Reference(
      final String[] sources,
      final int flags,
      final String className,
      final String superName,
      final String[] interfaces,
      final Field[] fields,
      final Method[] methods) {
    this.sources = sources;
    this.flags = flags;
    this.className = className;
    this.superName = superName;
    this.interfaces = interfaces;
    this.methods = methods;
    this.fields = fields;
  }

  /**
   * Create a new reference which combines this reference with another reference of the same type.
   *
   * @param anotherReference A reference to the same class
   * @return a new Reference which merges the two references
   */
  public Reference merge(final Reference anotherReference) {
    if (!anotherReference.className.equals(className)) {
      throw new IllegalStateException("illegal merge " + this + " != " + anotherReference);
    }
    return new Reference(
        Reference.merge(sources, anotherReference.sources),
        mergeFlags(flags, anotherReference.flags),
        className,
        null != this.superName ? this.superName : anotherReference.superName,
        Reference.merge(interfaces, anotherReference.interfaces),
        mergeFields(fields, anotherReference.fields),
        mergeMethods(methods, anotherReference.methods));
  }

  @Override
  public String toString() {
    return "Reference<" + className + ">";
  }

  public static final int EXPECTS_PUBLIC = 1;
  public static final int EXPECTS_PUBLIC_OR_PROTECTED = 2;
  public static final int EXPECTS_NON_PRIVATE = 4;
  public static final int EXPECTS_STATIC = 8;
  public static final int EXPECTS_NON_STATIC = 16;
  public static final int EXPECTS_INTERFACE = 32;
  public static final int EXPECTS_NON_INTERFACE = 64;
  public static final int EXPECTS_NON_FINAL = 128;

  public static boolean matches(int flags, int modifiers) {
    if ((flags & EXPECTS_PUBLIC) != 0 && (modifiers & Opcodes.ACC_PUBLIC) == 0) {
      return false;
    } else if ((flags & EXPECTS_PUBLIC_OR_PROTECTED) != 0
        && (modifiers & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) == 0) {
      return false;
    } else if ((flags & EXPECTS_NON_PRIVATE) != 0 && (modifiers & Opcodes.ACC_PRIVATE) != 0) {
      return false;
    } else if ((flags & EXPECTS_STATIC) != 0 && (modifiers & Opcodes.ACC_STATIC) == 0) {
      return false;
    } else if ((flags & EXPECTS_NON_STATIC) != 0 && (modifiers & Opcodes.ACC_STATIC) != 0) {
      return false;
    } else if ((flags & EXPECTS_INTERFACE) != 0 && (modifiers & Opcodes.ACC_INTERFACE) == 0) {
      return false;
    } else if ((flags & EXPECTS_NON_INTERFACE) != 0 && (modifiers & Opcodes.ACC_INTERFACE) != 0) {
      return false;
    } else if ((flags & EXPECTS_NON_FINAL) != 0 && (modifiers & Opcodes.ACC_FINAL) != 0) {
      return false;
    }
    return true;
  }

  public static class Field {
    public final String[] sources;
    public final int flags;
    public final String name;
    public final String fieldType;

    public Field(
        final String[] sources, final int flags, final String name, final String fieldType) {
      this.sources = sources;
      this.flags = flags;
      this.name = name;
      this.fieldType = fieldType;
    }

    public Field merge(final Field anotherField) {
      // consider both name and type when merging...
      if (!name.equals(anotherField.name) || !fieldType.equals(anotherField.fieldType)) {
        throw new IllegalStateException("illegal merge " + this + " != " + anotherField);
      }
      return new Field(
          Reference.merge(sources, anotherField.sources),
          mergeFlags(flags, anotherField.flags),
          name,
          fieldType);
    }

    @Override
    public String toString() {
      return name + fieldType;
    }

    @Override
    public boolean equals(final Object o) {
      if (o instanceof Field) {
        final Field other = (Field) o;
        return name.equals(other.name);
      }
      return false;
    }

    @Override
    public int hashCode() {
      return name.hashCode();
    }
  }

  public static class Method {
    public final String[] sources;
    public final int flags;
    public final String name;
    public final String methodType;

    public Method(
        final String[] sources, final int flags, final String name, final String methodType) {
      this.sources = sources;
      this.flags = flags;
      this.name = name;
      this.methodType = methodType;
    }

    public Method merge(final Method anotherMethod) {
      if (!equals(anotherMethod)) {
        throw new IllegalStateException("illegal merge " + this + " != " + anotherMethod);
      }
      return new Method(
          Reference.merge(sources, anotherMethod.sources),
          mergeFlags(flags, anotherMethod.flags),
          name,
          methodType);
    }

    @Override
    public String toString() {
      return name + methodType;
    }

    @Override
    public boolean equals(final Object o) {
      if (o instanceof Method) {
        final Method m = (Method) o;
        return name.equals(m.name) && methodType.equals(m.methodType);
      }
      return false;
    }

    @Override
    public int hashCode() {
      return toString().hashCode();
    }
  }

  /**
   * A mismatch between a Reference and a runtime class.
   *
   * <p>This class' toString returns a human-readable description of the mismatch along with
   * source-code locations of the instrumentation which caused the mismatch.
   */
  public abstract static class Mismatch {
    /** Instrumentation sources which caused the mismatch. */
    private final String[] mismatchSources;

    Mismatch(final String[] mismatchSources) {
      this.mismatchSources = mismatchSources;
    }

    @Override
    public String toString() {
      if (mismatchSources.length > 0) {
        return mismatchSources[0] + " " + getMismatchDetails();
      } else {
        return "<no-source> " + getMismatchDetails();
      }
    }

    /** Human-readable string describing the mismatch. */
    abstract String getMismatchDetails();

    public static class MissingClass extends Mismatch {
      private final String className;

      public MissingClass(final String[] sources, final String className) {
        super(sources);
        this.className = className;
      }

      @Override
      String getMismatchDetails() {
        return "Missing class " + className;
      }
    }

    public static class MissingFlag extends Mismatch {
      private final int expectedFlag;
      private final String classMethodOrFieldDesc;
      private final int foundAccess;

      public MissingFlag(
          final String[] sources,
          final String classMethodOrFieldDesc,
          final int expectedFlag,
          final int foundAccess) {
        super(sources);
        this.classMethodOrFieldDesc = classMethodOrFieldDesc;
        this.expectedFlag = expectedFlag;
        this.foundAccess = foundAccess;
      }

      @Override
      String getMismatchDetails() {
        return classMethodOrFieldDesc
            + " expected "
            + prettyPrint(expectedFlag)
            + " found "
            + Modifier.toString(foundAccess);
      }
    }

    public static class MissingField extends Mismatch {
      private final String className;
      private final String fieldName;
      private final String fieldDesc;

      public MissingField(
          final String[] sources,
          final String className,
          final String fieldName,
          final String fieldDesc) {
        super(sources);
        this.className = className;
        this.fieldName = fieldName;
        this.fieldDesc = fieldDesc;
      }

      @Override
      String getMismatchDetails() {
        return "Missing field " + className + "#" + fieldName + fieldDesc;
      }
    }

    public static class MissingMethod extends Mismatch {
      private final String className;
      private final String method;
      private final String methodType;

      public MissingMethod(
          final String[] sources,
          final String className,
          final String method,
          final String methodType) {
        super(sources);
        this.className = className;
        this.method = method;
        this.methodType = methodType;
      }

      @Override
      String getMismatchDetails() {
        return "Missing method " + className + "#" + method + methodType;
      }
    }

    /** Fallback mismatch in case an unexpected exception occurs during reference checking. */
    public static class ReferenceCheckError extends Mismatch {
      private final Exception referenceCheckException;
      private final Reference referenceBeingChecked;
      private final String location;

      public ReferenceCheckError(
          final Exception referenceCheckException,
          final Reference referenceBeingChecked,
          final String location) {
        super(new String[0]);
        this.referenceCheckException = referenceCheckException;
        this.referenceBeingChecked = referenceBeingChecked;
        this.location = location;
      }

      @Override
      String getMismatchDetails() {
        final StringWriter sw = new StringWriter();
        sw.write("Failed to generate reference check for: ");
        sw.write(referenceBeingChecked.toString());
        sw.write(" at ");
        sw.write(location);
        sw.write("\n");
        // add exception message and stack trace
        final PrintWriter pw = new PrintWriter(sw);
        referenceCheckException.printStackTrace(pw);
        return sw.toString();
      }
    }
  }

  public static class Builder {
    private final Set<String> sources = new LinkedHashSet<>();
    private int flags = 0;
    private final String className;
    private String superName = null;
    private final Set<String> interfaces = new LinkedHashSet<>();
    private final List<Field> fields = new ArrayList<>();
    private final List<Method> methods = new ArrayList<>();

    public Builder(final String className) {
      this.className = className;
    }

    public Builder withSuperName(final String superName) {
      this.superName = superName;
      return this;
    }

    public Builder withInterface(final String interfaceName) {
      interfaces.add(interfaceName);
      return this;
    }

    public Builder withSource(final String sourceName, final int line) {
      sources.add(sourceName + ":" + line);
      return this;
    }

    public Builder withFlag(final int flag) {
      flags |= flag;
      return this;
    }

    public Builder withField(
        final String[] sources,
        final int fieldFlags,
        final String fieldName,
        final Type fieldType) {
      return withField(sources, fieldFlags, fieldName, getDescriptor(fieldType));
    }

    public Builder withField(
        final String[] sources,
        final int fieldFlags,
        final String fieldName,
        final String fieldType) {
      final Field field = new Field(sources, fieldFlags, fieldName, fieldType);
      final int existingIndex = fields.indexOf(field);
      if (existingIndex == -1) {
        fields.add(field);
      } else {
        fields.set(existingIndex, field.merge(fields.get(existingIndex)));
      }
      return this;
    }

    public Builder withMethod(
        final String[] sources,
        final int methodFlags,
        final String methodName,
        final Type returnType,
        final Type... parameterTypes) {
      return withMethod(
          sources,
          methodFlags,
          methodName,
          getDescriptor(returnType),
          getDescriptors(parameterTypes));
    }

    public Builder withMethod(
        final String[] sources,
        final int methodFlags,
        final String methodName,
        final String returnType,
        final String... parameterTypes) {
      StringBuilder methodType = new StringBuilder().append('(');
      for (String parameterType : parameterTypes) {
        methodType.append(parameterType);
      }
      methodType.append(')').append(returnType);
      final Method method = new Method(sources, methodFlags, methodName, methodType.toString());
      final int existingIndex = methods.indexOf(method);
      if (existingIndex == -1) {
        methods.add(method);
      } else {
        methods.set(existingIndex, method.merge(methods.get(existingIndex)));
      }
      return this;
    }

    public Reference build() {
      return new Reference(
          sources.toArray(new String[sources.size()]),
          flags,
          Strings.getClassName(className),
          null != superName ? Strings.getClassName(superName) : null,
          interfaces.toArray(new String[interfaces.size()]),
          fields.toArray(new Field[fields.size()]),
          methods.toArray(new Method[methods.size()]));
    }

    /** Builds a reference that checks the next spec if the current spec doesn't match. */
    public Builder or() {
      return new OrBuilder(build());
    }
  }

  static class OrBuilder extends Builder {
    private final Reference either;
    private final List<Reference> ors;

    OrBuilder(Reference either) {
      super(either.className);
      this.either = either;
      this.ors = new ArrayList<>();
    }

    @Override
    public Reference build() {
      ors.add(super.build());
      return new OrReference(either, ors.toArray(new Reference[ors.size()]));
    }

    @Override
    public Builder or() {
      ors.add(super.build());
      return this;
    }
  }

  private static <E> E[] merge(final E[] array1, final E[] array2) {
    final Set<E> set = new LinkedHashSet<>((array1.length + array2.length) * 4 / 3);
    set.addAll(asList(array1));
    set.addAll(asList(array2));
    return set.toArray((E[]) Array.newInstance(array1.getClass().getComponentType(), set.size()));
  }

  private static int mergeFlags(final int flags1, final int flags2) {
    // TODO: Assert flags are non-contradictory and resolve
    // public > protected > package-private > private
    return flags1 | flags2;
  }

  private static Field[] mergeFields(final Field[] fields1, final Field[] fields2) {
    final List<Field> merged = new ArrayList<>(asList(fields1));
    for (final Field field : fields2) {
      final int i = merged.indexOf(field);
      if (i == -1) {
        merged.add(field);
      } else {
        merged.set(i, merged.get(i).merge(field));
      }
    }
    return merged.toArray(new Field[merged.size()]);
  }

  private static Method[] mergeMethods(final Method[] methods1, final Method[] methods2) {
    final List<Method> merged = new ArrayList<>(asList(methods1));
    for (final Method method : methods2) {
      final int i = merged.indexOf(method);
      if (i == -1) {
        merged.add(method);
      } else {
        merged.set(i, merged.get(i).merge(method));
      }
    }
    return merged.toArray(new Method[merged.size()]);
  }

  private static String getDescriptor(Type type) {
    return type.getDescriptor();
  }

  private static String[] getDescriptors(Type[] types) {
    String[] descriptors = new String[types.length];
    for (int i = 0; i < types.length; i++) {
      descriptors[i] = types[i].getDescriptor();
    }
    return descriptors;
  }

  static String prettyPrint(int flags) {
    StringBuilder buf = new StringBuilder();
    if ((flags & EXPECTS_PUBLIC) != 0) {
      buf.append("public ");
    }
    if ((flags & EXPECTS_PUBLIC_OR_PROTECTED) != 0) {
      buf.append("public_or_protected ");
    }
    if ((flags & EXPECTS_NON_PRIVATE) != 0) {
      buf.append("non_private ");
    }
    if ((flags & EXPECTS_STATIC) != 0) {
      buf.append("static ");
    }
    if ((flags & EXPECTS_NON_STATIC) != 0) {
      buf.append("non_static ");
    }
    if ((flags & EXPECTS_INTERFACE) != 0) {
      buf.append("interface ");
    }
    if ((flags & EXPECTS_NON_INTERFACE) != 0) {
      buf.append("non_interface ");
    }
    return buf.toString();
  }
}
