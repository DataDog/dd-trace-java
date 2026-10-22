package datadog.trace.test.util;

import static java.util.Collections.list;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import net.bytebuddy.jar.asm.AnnotationVisitor;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.FieldVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;

/**
 * Finds the synthetic accessors javac generates when classes in one nest use each other's {@code
 * private} members. Backs the checker contract of {@code
 * datadog.perfcontract.NoSyntheticAccessors}.
 *
 * <p>It reads the nest's class files rather than reflecting on loaded classes, for two reasons:
 * only the bytecode says which member an accessor reaches, and {@code @SuppressPerfContract} has
 * {@code CLASS} retention, so reflection cannot see it.
 */
public final class SyntheticAccessors {
  private static final String SUPPRESS_DESC = "Ldatadog/perfcontract/SuppressPerfContract;";
  private static final String CONTRACT_DESC = "Ldatadog/perfcontract/NoSyntheticAccessors;";

  private SyntheticAccessors() {}

  /** Fails if any class in {@code topLevel}'s nest has an unsuppressed synthetic accessor. */
  public static void assertNone(Class<?> topLevel) {
    List<String> accessors = find(topLevel);
    if (!accessors.isEmpty()) {
      throw new AssertionError(
          "Synthetic accessors in the nest of "
              + topLevel.getName()
              + "; make the accessed members package-private, or suppress them with"
              + " @SuppressPerfContract(value = NoSyntheticAccessors.class, reason = ...):\n  "
              + String.join("\n  ", accessors));
    }
  }

  /**
   * Lists the unsuppressed synthetic accessors in {@code topLevel}'s nest: methods named {@code
   * access$*} and synthetic constructors, each as {@code Class.member -> Owner.target}.
   *
   * <p>An accessor is suppressed when the member it reaches carries {@code @SuppressPerfContract}
   * naming {@code NoSyntheticAccessors} with a non-blank reason, directly or through an annotation
   * that is itself annotated that way.
   */
  public static List<String> find(Class<?> topLevel) {
    if (topLevel.getEnclosingClass() != null) {
      throw new IllegalArgumentException("not a top-level class: " + topLevel.getName());
    }
    ClassLoader loader = topLevel.getClassLoader();
    Map<String, NestClass> nest = new HashMap<>();
    for (String binaryName : nestClassNames(topLevel)) {
      NestClass nestClass = NestClass.read(loader, binaryName.replace('.', '/'));
      nest.put(nestClass.internalName, nestClass);
    }

    List<String> accessors = new ArrayList<>();
    Map<String, Boolean> suppressingAnnotations = new HashMap<>();
    for (NestClass nestClass : nest.values()) {
      for (Accessor accessor : nestClass.accessors) {
        Member target = accessor.target == null ? null : resolve(nest, accessor.target);
        String problem = suppressionProblem(loader, target, suppressingAnnotations);
        if (problem != null) {
          accessors.add(accessor.describe(nestClass, target) + problem);
        }
      }
    }
    accessors.sort(null);
    return accessors;
  }

  /** The target as declared in the nest, or {@code null} when it is declared outside it. */
  private static Member resolve(Map<String, NestClass> nest, MemberRef ref) {
    NestClass owner = nest.get(ref.owner);
    return owner == null ? null : owner.members.get(ref.key());
  }

  /**
   * Returns {@code null} when {@code target} suppresses the contract, else the reason it doesn't,
   * formatted as a suffix for the report.
   */
  private static String suppressionProblem(
      ClassLoader loader, Member target, Map<String, Boolean> suppressingAnnotations) {
    if (target == null) {
      return "";
    }
    if (target.suppression != null) {
      return target.suppression.problem();
    }
    for (String annotationDesc : target.annotations) {
      if (suppressingAnnotations.computeIfAbsent(
          annotationDesc, desc -> isSuppressingAnnotation(loader, desc))) {
        return null;
      }
    }
    return "";
  }

  /** Whether the annotation type {@code desc} is meta-annotated with a valid suppression. */
  private static boolean isSuppressingAnnotation(ClassLoader loader, String desc) {
    String internalName = Type.getType(desc).getInternalName();
    if (loader.getResource(internalName + ".class") == null) {
      return false;
    }
    Suppression suppression = NestClass.read(loader, internalName).typeSuppression;
    return suppression != null && suppression.problem() == null;
  }

  /**
   * Lists {@code topLevel} and every class file named {@code TopLevel$*} beside it. Anonymous and
   * local classes aren't reachable through reflection, so the class files are listed directly.
   */
  private static List<String> nestClassNames(Class<?> topLevel) {
    String binaryPrefix = topLevel.getName() + "$";
    String filePrefix = topLevel.getSimpleName() + "$";
    URL url = topLevel.getResource(topLevel.getSimpleName() + ".class");
    if (url == null) {
      throw new IllegalStateException("no class file for " + topLevel.getName());
    }
    List<String> names = new ArrayList<>();
    names.add(topLevel.getName());
    try {
      if ("file".equals(url.getProtocol())) {
        File[] files = new File(url.toURI()).getParentFile().listFiles();
        if (files != null) {
          for (File file : files) {
            addNestedName(file.getName(), filePrefix, binaryPrefix, names);
          }
        }
      } else if ("jar".equals(url.getProtocol())) {
        String entryDir = topLevel.getName().replace('.', '/');
        entryDir = entryDir.substring(0, entryDir.lastIndexOf('/') + 1);
        JarFile jar = ((JarURLConnection) url.openConnection()).getJarFile();
        for (JarEntry entry : list(jar.entries())) {
          String name = entry.getName();
          if (name.startsWith(entryDir) && name.indexOf('/', entryDir.length()) < 0) {
            addNestedName(name.substring(entryDir.length()), filePrefix, binaryPrefix, names);
          }
        }
      } else {
        throw new IllegalStateException("unsupported class location: " + url);
      }
    } catch (IOException | URISyntaxException e) {
      throw new IllegalStateException("cannot list the nest of " + topLevel.getName(), e);
    }
    return names;
  }

  private static void addNestedName(
      String fileName, String filePrefix, String binaryPrefix, List<String> names) {
    if (fileName.startsWith(filePrefix) && fileName.endsWith(".class")) {
      String suffix = fileName.substring(filePrefix.length(), fileName.length() - 6);
      names.add(binaryPrefix + suffix);
    }
  }

  /** A field or method reference: owner, name and descriptor, as in the bytecode. */
  private static final class MemberRef {
    final String owner;
    final String name;
    final String desc;

    MemberRef(String owner, String name, String desc) {
      this.owner = owner;
      this.name = name;
      this.desc = desc;
    }

    String key() {
      return name + desc;
    }
  }

  /** A declared field, method or constructor, with the annotations the checker needs. */
  private static final class Member {
    final String name;
    final List<String> annotations = new ArrayList<>();
    Suppression suppression;

    Member(String name) {
      this.name = name;
    }
  }

  /** An {@code @SuppressPerfContract}'s contracts and reason. */
  private static final class Suppression {
    final List<String> contracts = new ArrayList<>();
    String reason;

    /**
     * Returns {@code null} when this suppresses {@code NoSyntheticAccessors}, else the reason it
     * doesn't, formatted as a report suffix.
     */
    String problem() {
      if (!contracts.contains(CONTRACT_DESC)) {
        return " (its @SuppressPerfContract does not name NoSyntheticAccessors)";
      }
      if (reason == null || reason.trim().isEmpty()) {
        return " (its @SuppressPerfContract has a blank reason)";
      }
      return null;
    }

    AnnotationVisitor visitor() {
      return new AnnotationVisitor(Opcodes.ASM9) {
        @Override
        public void visit(String name, Object value) {
          if ("reason".equals(name)) {
            reason = (String) value;
          }
        }

        @Override
        public AnnotationVisitor visitArray(String name) {
          if (!"value".equals(name)) {
            return null;
          }
          return new AnnotationVisitor(Opcodes.ASM9) {
            @Override
            public void visit(String unused, Object value) {
              contracts.add(((Type) value).getDescriptor());
            }
          };
        }
      };
    }
  }

  /** A synthetic accessor and the member it reaches, when that can be told from its bytecode. */
  private static final class Accessor {
    final String name;
    final String desc;
    MemberRef target;

    Accessor(String name, String desc) {
      this.name = name;
      this.desc = desc;
    }

    String describe(NestClass owner, Member resolvedTarget) {
      String self =
          owner.binaryName()
              + ("<init>".equals(name)
                  ? ".<init>" + Type.getArgumentTypes(desc).length
                  : "." + name);
      if (target == null) {
        return self;
      }
      String targetName = resolvedTarget == null ? target.name : resolvedTarget.name;
      return self + " -> " + target.owner.replace('/', '.') + "." + targetName;
    }
  }

  /** The parts of one nest class file the checker needs. */
  private static final class NestClass extends ClassVisitor {
    String internalName;
    final Map<String, Member> members = new HashMap<>();
    final List<Accessor> accessors = new ArrayList<>();
    Suppression typeSuppression;

    private NestClass() {
      super(Opcodes.ASM9);
    }

    static NestClass read(ClassLoader loader, String internalName) {
      NestClass nestClass = new NestClass();
      try (InputStream in = loader.getResourceAsStream(internalName + ".class")) {
        if (in == null) {
          throw new IllegalStateException("no class file for " + internalName);
        }
        new ClassReader(in).accept(nestClass, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
      } catch (IOException e) {
        throw new IllegalStateException("cannot read " + internalName, e);
      }
      return nestClass;
    }

    String binaryName() {
      return internalName.replace('/', '.');
    }

    @Override
    public void visit(
        int version,
        int access,
        String name,
        String signature,
        String superName,
        String[] interfaces) {
      this.internalName = name;
    }

    @Override
    public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
      if (SUPPRESS_DESC.equals(desc)) {
        typeSuppression = new Suppression();
        return typeSuppression.visitor();
      }
      return null;
    }

    @Override
    public FieldVisitor visitField(
        int access, String name, String desc, String signature, Object value) {
      Member member = new Member(name);
      members.put(name + desc, member);
      return new FieldVisitor(Opcodes.ASM9) {
        @Override
        public AnnotationVisitor visitAnnotation(String annotationDesc, boolean visible) {
          return annotate(member, annotationDesc);
        }
      };
    }

    @Override
    public MethodVisitor visitMethod(
        int access, String name, String desc, String signature, String[] exceptions) {
      Member member = new Member(name);
      members.put(name + desc, member);
      boolean synthetic = (access & Opcodes.ACC_SYNTHETIC) != 0;
      Accessor accessor =
          synthetic && (name.startsWith("access$") || "<init>".equals(name))
              ? new Accessor(name, desc)
              : null;
      if (accessor != null) {
        accessors.add(accessor);
      }
      return new MethodVisitor(Opcodes.ASM9) {
        @Override
        public AnnotationVisitor visitAnnotation(String annotationDesc, boolean visible) {
          return annotate(member, annotationDesc);
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String fieldName, String fieldDesc) {
          if (accessor != null && accessor.target == null) {
            accessor.target = new MemberRef(owner, fieldName, fieldDesc);
          }
        }

        @Override
        public void visitMethodInsn(
            int opcode, String owner, String methodName, String methodDesc, boolean itf) {
          if (accessor == null || accessor.target != null) {
            return;
          }
          // A synthetic constructor reaches the private constructor of its own class.
          boolean constructor = "<init>".equals(accessor.name);
          if (!constructor || ("<init>".equals(methodName) && owner.equals(internalName))) {
            accessor.target = new MemberRef(owner, methodName, methodDesc);
          }
        }
      };
    }

    private static AnnotationVisitor annotate(Member member, String annotationDesc) {
      if (SUPPRESS_DESC.equals(annotationDesc)) {
        member.suppression = new Suppression();
        return member.suppression.visitor();
      }
      member.annotations.add(annotationDesc);
      return null;
    }
  }
}
