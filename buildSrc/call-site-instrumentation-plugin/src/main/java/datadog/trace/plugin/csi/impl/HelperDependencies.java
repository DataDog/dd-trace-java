package datadog.trace.plugin.csi.impl;

import static datadog.trace.plugin.csi.util.CallSiteConstants.ASM_API_VERSION;
import static org.objectweb.asm.ClassReader.SKIP_DEBUG;
import static org.objectweb.asm.ClassReader.SKIP_FRAMES;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

/**
 * Expands the helpers of a call site to every class of the module they depend on, in an order
 * {@code HelperInjector} can inject them.
 *
 * <p>Helpers declared in {@code @CallSite(helpers = ...)} are injected exactly as listed, so a
 * class they need but do not list, such as an anonymous class, is missing from any class loader the
 * call site injects into on its own. This mirrors the agent's {@code HelperScanner}: supertypes and
 * enclosing classes are needed to define a class, everything referenced from fields, signatures and
 * instructions may be needed to run it. Only classes compiled into the module are followed;
 * anything else is expected to come from the agent's bootstrap or the application.
 */
public final class HelperDependencies {

  private final Path classesFolder;

  /** Classes each helper needs defined before it, keyed by internal name. */
  private final Map<String, Set<String>> requires = new LinkedHashMap<>();

  private HelperDependencies(final Path classesFolder) {
    this.classesFolder = classesFolder;
  }

  /**
   * Returns {@code helpers} plus every module class they depend on, ordered so that each class
   * comes after the supertypes and enclosing classes it needs. Declared helpers are kept even when
   * they are not part of the module.
   */
  @Nonnull
  public static Type[] expand(@Nonnull final Path classesFolder, @Nonnull final Type[] helpers) {
    return new HelperDependencies(classesFolder).expand(helpers);
  }

  private Type[] expand(final Type[] helpers) {
    final Deque<String> work = new ArrayDeque<>();
    for (final Type helper : helpers) {
      requires.put(helper.getInternalName(), new LinkedHashSet<>());
      work.add(helper.getInternalName());
    }
    final Set<String> scanned = new HashSet<>();
    String name;
    while ((name = work.poll()) != null) {
      if (scanned.add(name)) {
        final Path file = classFile(name);
        if (Files.isRegularFile(file)) {
          final Scanner scanner = new Scanner(name);
          new ClassReader(read(file)).accept(scanner, SKIP_DEBUG | SKIP_FRAMES);
          requires.put(name, scanner.requires);
          for (final String used : scanner.uses) {
            if (!scanned.contains(used) && Files.isRegularFile(classFile(used))) {
              requires.putIfAbsent(used, new LinkedHashSet<>());
              work.add(used);
            }
          }
        }
      }
    }
    return inDefinitionOrder();
  }

  /**
   * Orders classes so each follows what it needs, breaking cycles as {@code HelperScanner} does.
   */
  private Type[] inDefinitionOrder() {
    final Set<String> visited = new HashSet<>();
    final Set<String> path = new HashSet<>();
    for (final String name : requires.keySet()) {
      removeCycles(name, visited, path);
    }
    final Set<String> ordered = new LinkedHashSet<>();
    while (ordered.size() < requires.size()) {
      boolean progress = false;
      for (final Map.Entry<String, Set<String>> entry : requires.entrySet()) {
        if (!ordered.contains(entry.getKey()) && ordered.containsAll(entry.getValue())) {
          ordered.add(entry.getKey());
          progress = true;
        }
      }
      if (!progress) {
        throw new IllegalStateException("Unable to order call site helpers: " + requires);
      }
    }
    return ordered.stream().map(Type::getObjectType).toArray(Type[]::new);
  }

  private void removeCycles(final String name, final Set<String> visited, final Set<String> path) {
    if (visited.add(name)) {
      path.add(name);
      final Iterator<String> itr = requires.get(name).iterator();
      while (itr.hasNext()) {
        final String next = itr.next();
        if (path.contains(next) || !requires.containsKey(next) || next.startsWith(name + '$')) {
          itr.remove();
        } else {
          removeCycles(next, visited, path);
        }
      }
      path.remove(name);
    }
  }

  private Path classFile(final String internalName) {
    return classesFolder.resolve(internalName + ".class");
  }

  private static byte[] read(final Path file) {
    try {
      return Files.readAllBytes(file);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Collects the classes one class needs to be defined, and the classes it uses. */
  private static final class Scanner extends ClassVisitor {
    private final String name;
    final Set<String> requires = new LinkedHashSet<>();
    final Set<String> uses = new LinkedHashSet<>();
    private final MethodVisitor methodScanner = new MethodScanner();

    Scanner(final String name) {
      super(ASM_API_VERSION);
      this.name = name;
    }

    @Override
    public void visit(
        final int version,
        final int access,
        final String name,
        final String signature,
        final String superName,
        final String[] interfaces) {
      require(superName);
      if (interfaces != null) {
        for (final String type : interfaces) {
          require(type);
        }
      }
    }

    @Override
    public void visitInnerClass(
        final String name, final String outerName, final String innerName, final int access) {
      if (this.name.equals(name)) {
        require(outerName);
      }
    }

    @Override
    public FieldVisitor visitField(
        final int access,
        final String name,
        final String descriptor,
        final String signature,
        final Object value) {
      use(Type.getType(descriptor));
      return null;
    }

    @Override
    public MethodVisitor visitMethod(
        final int access,
        final String name,
        final String descriptor,
        final String signature,
        final String[] exceptions) {
      use(Type.getMethodType(descriptor));
      if (exceptions != null) {
        for (final String type : exceptions) {
          use(Type.getObjectType(type));
        }
      }
      return methodScanner;
    }

    private void require(final String internalName) {
      if (internalName != null && !internalName.equals(name)) {
        requires.add(internalName);
        uses.add(internalName);
      }
    }

    private void use(Type type) {
      while (type.getSort() == Type.ARRAY) {
        type = type.getElementType();
      }
      if (type.getSort() == Type.METHOD) {
        for (final Type argument : type.getArgumentTypes()) {
          use(argument);
        }
        use(type.getReturnType());
      } else if (type.getSort() == Type.OBJECT && !type.getInternalName().equals(name)) {
        uses.add(type.getInternalName());
      }
    }

    private void use(final Handle handle) {
      use(Type.getObjectType(handle.getOwner()));
      use(Type.getType(handle.getDesc()));
    }

    private void useConstant(final Object value) {
      if (value instanceof Type) {
        use((Type) value);
      } else if (value instanceof Handle) {
        use((Handle) value);
      }
    }

    /** Finds the classes used by method instructions. */
    private final class MethodScanner extends MethodVisitor {
      MethodScanner() {
        super(ASM_API_VERSION);
      }

      @Override
      public void visitTypeInsn(final int opcode, final String type) {
        use(Type.getObjectType(type));
      }

      @Override
      public void visitFieldInsn(
          final int opcode, final String owner, final String name, final String descriptor) {
        use(Type.getObjectType(owner));
        use(Type.getType(descriptor));
      }

      @Override
      public void visitMethodInsn(
          final int opcode,
          final String owner,
          final String name,
          final String descriptor,
          final boolean isInterface) {
        use(Type.getObjectType(owner));
        use(Type.getMethodType(descriptor));
      }

      @Override
      public void visitInvokeDynamicInsn(
          final String name,
          final String descriptor,
          final Handle bootstrapMethodHandle,
          final Object... bootstrapMethodArguments) {
        use(Type.getMethodType(descriptor));
        use(bootstrapMethodHandle);
        for (final Object argument : bootstrapMethodArguments) {
          useConstant(argument);
        }
      }

      @Override
      public void visitLdcInsn(final Object value) {
        useConstant(value);
      }

      @Override
      public void visitMultiANewArrayInsn(final String descriptor, final int numDimensions) {
        use(Type.getType(descriptor));
      }

      @Override
      public void visitTryCatchBlock(
          final Label start, final Label end, final Label handler, final String type) {
        if (type != null) {
          use(Type.getObjectType(type));
        }
      }
    }
  }
}
