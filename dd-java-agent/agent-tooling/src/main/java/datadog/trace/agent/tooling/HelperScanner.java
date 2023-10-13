package datadog.trace.agent.tooling;

import datadog.trace.bootstrap.Constants;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.FieldVisitor;
import net.bytebuddy.jar.asm.Handle;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;

/** Scans helper classes to find what classes they depend on and what order to load them. */
public final class HelperScanner extends ClassVisitor {
  static final int READER_OPTIONS = ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES;

  static final ClassFileLocator locator =
      ClassFileLocator.ForClassLoader.of(Utils.getAgentClassLoader());

  final MethodScanner methodScanner = new MethodScanner();

  final Consumer<String> REQUIRES = this::requiresClass;
  final Consumer<String> USES = this::usesClass;

  final Map<String, Set<String>> classGraph = new LinkedHashMap<>();
  final Set<String> search = new HashSet<>();
  final Set<String> visited = new HashSet<>();

  String className;
  Set<String> requires;
  Set<String> uses;

  HelperScanner() {
    super(Opcodes.ASM7, null);
  }

  /** Expands helper class names to include any non-bootstrap classes they depend on. */
  public static String[] withClassDependencies(String... helperClassNames) {
    return new HelperScanner().simulateClassLoading(helperClassNames);
  }

  /**
   * Simulates class-loading by finding all classes required to load the helper classes as well as
   * optional classes used in method instructions that may be needed later when invoking the method.
   * Classes are arranged in order of loading to satisfy the constraints of {@link HelperInjector}.
   *
   * <p>Bootstrap types are not included in the list.
   */
  String[] simulateClassLoading(String... helperClassNames) {
    Deque<String> workQueue = new ArrayDeque<>();

    for (String className : helperClassNames) {
      workQueue.addLast(className);
      // keep root names in the final list even if they're not loadable at this point
      classGraph.put(className, Collections.emptySet());
    }

    // scan each class in turn, adding new types to the work queue
    while ((className = workQueue.pollFirst()) != null) {
      if (visited.add(className)) {
        try {
          byte[] bytecode = locator.locate(className).resolve();

          requires = new LinkedHashSet<>();
          uses = new LinkedHashSet<>();

          new ClassReader(bytecode).accept(this, READER_OPTIONS);

          classGraph.put(className, requires);
          uses.removeAll(visited);
          workQueue.addAll(uses);
        } catch (Throwable ignore) {
        }
      }
    }

    visited.clear();
    for (String className : classGraph.keySet()) {
      removeCycles(className);
    }

    // load types without any dependencies, then load those satisfied by what's loaded so far...
    // (this assumes that the class graph has had cycles removed and is a directed acyclic graph)
    Set<String> loaded = new LinkedHashSet<>();
    while (!classGraph.isEmpty()) {
      boolean unchanged = true;
      Iterator<Map.Entry<String, Set<String>>> itr = classGraph.entrySet().iterator();
      while (itr.hasNext()) {
        Map.Entry<String, Set<String>> node = itr.next();
        if (loaded.containsAll(node.getValue())) {
          loaded.add(node.getKey());
          itr.remove();
          unchanged = false;
        }
      }
      if (unchanged) {
        throw new IllegalStateException("Unable to resolve load order for: " + classGraph);
      }
    }
    return loaded.toArray(new String[0]);
  }

  /** Simple depth-first search to make sure we end up with a directed acyclic graph. */
  void removeCycles(String className) {
    if (visited.add(className)) {
      search.add(className);
      Iterator<String> itr = classGraph.get(className).iterator();
      while (itr.hasNext()) {
        String nextName = itr.next();
        if (search.contains(nextName) // cycle detected, remove link to break it
            || !classGraph.containsKey(nextName) // remove any non-loadable types
            || nextName.startsWith(className + "$")) { // skip links to inner types
          itr.remove();
        } else {
          removeCycles(nextName);
        }
      }
      search.remove(className);
    }
  }

  /** Types that contribute to the helper class shape/hierarchy are required at load-time. */
  @Override
  public void visit(
      final int version,
      final int access,
      final String name,
      final String signature,
      final String superName,
      final String[] interfaces) {
    record(superName, REQUIRES);
    record(interfaces, REQUIRES);
  }

  @Override
  public void visitInnerClass(String name, String outerName, String innerName, int access) {
    if (this.className.equals(name)) {
      record(outerName, REQUIRES);
    }
  }

  @Override
  public FieldVisitor visitField(
      final int access,
      final String name,
      final String descriptor,
      final String signature,
      final Object value) {
    record(Type.getType(descriptor), REQUIRES);
    return null;
  }

  @Override
  public MethodVisitor visitMethod(
      final int access,
      final String name,
      final String descriptor,
      final String signature,
      final String[] exceptions) {
    record(Type.getMethodType(descriptor), REQUIRES);
    record(exceptions, REQUIRES);
    return methodScanner;
  }

  /** Attempts to find all types used in method instructions by the helper class. */
  class MethodScanner extends MethodVisitor {
    MethodScanner() {
      super(Opcodes.ASM7, null);
    }

    @Override
    public void visitFieldInsn(
        final int opcode, final String owner, final String name, final String descriptor) {
      record(Type.getObjectType(owner), USES);
      record(Type.getType(descriptor), USES);
    }

    @Override
    public void visitMethodInsn(
        final int opcode,
        final String owner,
        final String name,
        final String descriptor,
        final boolean isInterface) {
      record(Type.getObjectType(owner), USES);
      record(Type.getMethodType(descriptor), USES);
    }

    @Override
    public void visitTypeInsn(final int opcode, final String type) {
      record(Type.getObjectType(type), USES);
    }

    @Override
    public void visitInvokeDynamicInsn(
        String name,
        String descriptor,
        Handle bootstrapMethodHandle,
        Object... bootstrapMethodArguments) {
      record(Type.getType(descriptor), USES);
      record(bootstrapMethodHandle, USES);
      for (Object value : bootstrapMethodArguments) {
        if (value instanceof Type) {
          record((Type) value, USES);
        } else if (value instanceof Handle) {
          record((Handle) value, USES);
        }
      }
    }

    @Override
    public void visitLdcInsn(final Object value) {
      if (value instanceof Type) {
        record((Type) value, USES);
      } else if (value instanceof Handle) {
        record((Handle) value, USES);
      }
    }
  }

  /** Marks a class as required; the helper won't load if this class hasn't been loaded first. */
  void requiresClass(String className) {
    requires.add(className);
    uses.add(className);
  }

  /** Marks a class as used; the helper doesn't need it at load time but may use it when called. */
  void usesClass(String className) {
    uses.add(className);
  }

  void record(Type type, Consumer<String> action) {
    if (null != type) {
      while (type.getSort() == Type.ARRAY) {
        type = type.getElementType();
      }
      if (type.getSort() == Type.METHOD) {
        record(type.getArgumentTypes(), action);
        record(type.getReturnType(), action);
      } else if (type.getSort() == Type.OBJECT) {
        String className = type.getClassName();
        // ignore types that we expect to be on the boot-class-path
        if (this.className.equals(className)
            || className.startsWith("java.")
            || className.startsWith("javax.")
            || className.startsWith("jdk.")
            || className.startsWith("com.sun.")
            || className.startsWith("sun.")
            || className.startsWith("org.slf4j.")
            || className.startsWith("datadog.slf4j.")) {
          return;
        }
        for (String prefix : Constants.BOOTSTRAP_PACKAGE_PREFIXES) {
          if (className.startsWith(prefix)) {
            return;
          }
        }
        action.accept(className);
      }
    }
  }

  void record(Type[] types, Consumer<String> action) {
    if (null != types) {
      for (Type t : types) {
        record(t, action);
      }
    }
  }

  void record(Handle handle, Consumer<String> action) {
    if (null != handle) {
      record(Type.getObjectType(handle.getOwner()), action);
      record(Type.getType(handle.getDesc()), action);
    }
  }

  void record(String internalName, Consumer<String> action) {
    if (null != internalName) {
      record(Type.getObjectType(internalName), action);
    }
  }

  void record(String[] internalNames, Consumer<String> action) {
    if (null != internalNames) {
      for (String n : internalNames) {
        record(Type.getObjectType(n), action);
      }
    }
  }
}
