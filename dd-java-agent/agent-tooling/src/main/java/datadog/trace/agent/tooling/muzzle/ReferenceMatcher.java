package datadog.trace.agent.tooling.muzzle;

import static datadog.trace.agent.tooling.ClassLoaderMatcher.BOOTSTRAP_CLASSLOADER;
import static net.bytebuddy.dynamic.loading.ClassLoadingStrategy.BOOTSTRAP_LOADER;

import datadog.trace.agent.tooling.Utils;
import datadog.trace.agent.tooling.muzzle.Reference.Mismatch;
import datadog.trace.agent.tooling.muzzle.Reference.Source;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;

/** Matches a set of references against a classloader. */
@Slf4j
public class ReferenceMatcher {
  private final Map<ClassLoader, List<Reference.Mismatch>> mismatchCache =
      Collections.synchronizedMap(new WeakHashMap<ClassLoader, List<Reference.Mismatch>>());
  private final Reference[] references;
  private final Set<String> helperClassNames;

  public ReferenceMatcher(Reference... references) {
    this(new String[0], references);
  }

  public ReferenceMatcher(String[] helperClassNames, Reference[] references) {
    this.references = references;
    this.helperClassNames = new HashSet<>(Arrays.asList(helperClassNames));
  }

  /**
   * @param loader Classloader to validate against (or null for bootstrap)
   * @return true if all references match the classpath of loader
   */
  public boolean matches(ClassLoader loader) {
    return getMismatchedReferenceSources(loader).size() == 0;
  }

  /**
   * @param loader Classloader to validate against (or null for bootstrap)
   * @return A list of all mismatches between this ReferenceMatcher and loader's classpath.
   */
  public List<Reference.Mismatch> getMismatchedReferenceSources(ClassLoader loader) {
    if (loader == BOOTSTRAP_LOADER) {
      loader = Utils.getBootstrapProxy();
    }
    List<Reference.Mismatch> mismatches = mismatchCache.get(loader);
    if (null == mismatches) {
      synchronized (loader) {
        mismatches = mismatchCache.get(loader);
        if (null == mismatches) {
          mismatches = new ArrayList<>(0);
          for (Reference reference : references) {
            // Don't reference-check helper classes.
            // They will be injected by the instrumentation's HelperInjector.
            if (!helperClassNames.contains(reference.getClassName())) {
              mismatches.addAll(checkMatch(reference, loader));
            }
          }
          mismatchCache.put(loader, mismatches);
        }
      }
    }
    return mismatches;
  }

  /**
   * Check a reference against a classloader's classpath.
   *
   * @param loader
   * @return A list of mismatched sources. A list of size 0 means the reference matches the class.
   */
  private static List<Reference.Mismatch> checkMatch(Reference reference, ClassLoader loader) {
    if (loader == BOOTSTRAP_CLASSLOADER) {
      throw new IllegalStateException("Cannot directly check against bootstrap classloader");
    }
    if (!onClasspath(reference.getClassName(), loader)) {
      return Collections.<Mismatch>singletonList(
          new Mismatch.MissingClass(
              reference.getSources().toArray(new Source[0]), reference.getClassName()));
    }
    final List<Mismatch> mismatches = new ArrayList<>(0);
    try {
      ReferenceMatcher.UnloadedType typeOnClasspath =
          ReferenceMatcher.UnloadedType.of(reference.getClassName(), loader);
      mismatches.addAll(typeOnClasspath.checkMatch(reference));
      for (Reference.Method requiredMethod : reference.getMethods()) {
        mismatches.addAll(typeOnClasspath.checkMatch(requiredMethod));
      }
    } catch (Exception e) {
      // Shouldn't happen. Fail the reference check and add a mismatch for debug logging.
      mismatches.add(new Mismatch.ReferenceCheckError(e));
    }
    return mismatches;
  }

  private static boolean onClasspath(final String className, final ClassLoader loader) {
    final String resourceName = Utils.getResourceName(className);
    return loader.getResource(resourceName) != null
        // we can also reach bootstrap classes
        || Utils.getBootstrapProxy().getResource(resourceName) != null;
  }

  /**
   * A representation of a jvm class created from a byte array without loading the class in
   * question.
   *
   * <p>Used to compare an expected Reference with the actual runtime class without causing
   * classloads.
   */
  public static class UnloadedType extends ClassVisitor {
    private static final Map<ClassLoader, Map<String, UnloadedType>> typeCache =
        Collections.synchronizedMap(new WeakHashMap<ClassLoader, Map<String, UnloadedType>>());

    private String superName = null;
    private String className = null;
    private String[] interfaceNames = new String[0];
    private UnloadedType unloadedSuper = null;
    private final List<UnloadedType> unloadedInterfaces = new ArrayList<>();
    private int flags;
    private final List<Method> methods = new ArrayList<>();

    public static UnloadedType of(String className, ClassLoader classLoader) throws Exception {
      className = Utils.getInternalName(className);
      Map<String, UnloadedType> classLoaderCache = typeCache.get(classLoader);
      if (classLoaderCache == null) {
        synchronized (classLoader) {
          classLoaderCache = typeCache.get(classLoader);
          if (classLoaderCache == null) {
            classLoaderCache = new ConcurrentHashMap<>();
            typeCache.put(classLoader, classLoaderCache);
          }
        }
      }
      UnloadedType unloadedType = classLoaderCache.get(className);
      if (unloadedType == null) {
        final InputStream in = classLoader.getResourceAsStream(Utils.getResourceName(className));
        unloadedType = new UnloadedType(null);
        final ClassReader reader = new ClassReader(in);
        reader.accept(unloadedType, ClassReader.SKIP_CODE);
        if (unloadedType.superName != null) {
          unloadedType.unloadedSuper = UnloadedType.of(unloadedType.superName, classLoader);
        }
        for (String interfaceName : unloadedType.interfaceNames) {
          unloadedType.unloadedInterfaces.add(UnloadedType.of(interfaceName, classLoader));
        }
        classLoaderCache.put(className, unloadedType);
      }
      return unloadedType;
    }

    private UnloadedType(ClassVisitor cv) {
      super(Opcodes.ASM6, cv);
    }

    public String getClassName() {
      return className;
    }

    public String getSuperName() {
      return superName;
    }

    public int getFlags() {
      return flags;
    }

    public List<Reference.Mismatch> checkMatch(Reference reference) {
      final List<Reference.Mismatch> mismatches = new ArrayList<>(0);
      for (Reference.Flag flag : reference.getFlags()) {
        if (!flag.matches(getFlags())) {
          final String desc = this.getClassName();
          mismatches.add(
              new Mismatch.MissingFlag(
                  reference.getSources().toArray(new Source[0]), desc, flag, getFlags()));
        }
      }
      return mismatches;
    }

    public List<Reference.Mismatch> checkMatch(Reference.Method method) {
      final List<Reference.Mismatch> mismatches = new ArrayList<>(0);
      // does the method exist?
      Method unloadedMethod = findMethod(method, true);
      if (unloadedMethod == null) {
        mismatches.add(
            new Reference.Mismatch.MissingMethod(
                method.getSources().toArray(new Reference.Source[0]),
                className,
                method.toString()));
      } else {
        for (Reference.Flag flag : method.getFlags()) {
          if (!flag.matches(unloadedMethod.getFlags())) {
            final String desc = this.getClassName() + "#" + unloadedMethod.signature;
            mismatches.add(
                new Mismatch.MissingFlag(
                    method.getSources().toArray(new Source[0]),
                    desc,
                    flag,
                    unloadedMethod.getFlags()));
          }
        }
      }
      return mismatches;
    }

    private Method findMethod(Reference.Method method, boolean includePrivateMethods) {
      Method unloadedMethod =
          new Method(
              0,
              method.getName(),
              Type.getMethodType(
                      method.getReturnType(), method.getParameterTypes().toArray(new Type[0]))
                  .getDescriptor());
      for (Method meth : methods) {
        if (meth.equals(unloadedMethod)) {
          if (meth.is(Opcodes.ACC_PRIVATE)) {
            return includePrivateMethods ? meth : null;
          } else {
            return meth;
          }
        }
      }
      if (null != unloadedSuper) {
        final Method meth = unloadedSuper.findMethod(method, false);
        if (null != meth) return meth;
      }
      for (UnloadedType unloadedInterface : unloadedInterfaces) {
        final Method meth = unloadedInterface.findMethod(method, false);
        if (null != meth) return meth;
      }
      return null;
    }

    public boolean hasField(Reference.Field field) {
      // TODO does the field exist?
      // TODO are the expected field flags present (static, public, etc)
      throw new RuntimeException("TODO");
    }

    @Override
    public void visit(
        final int version,
        final int access,
        final String name,
        final String signature,
        final String superName,
        final String[] interfaces) {
      className = Utils.getClassName(name);
      if (null != superName) this.superName = Utils.getClassName(superName);
      if (null != interfaces) this.interfaceNames = interfaces;
      this.flags = access;
      super.visit(version, access, name, signature, superName, interfaces);
    }

    @Override
    public MethodVisitor visitMethod(
        final int access,
        final String name,
        final String descriptor,
        final String signature,
        final String[] exceptions) {
      // Additional references we could check
      // - Classes in signature (return type, params) and visible from this package
      methods.add(new Method(access, name, descriptor));
      return super.visitMethod(access, name, descriptor, signature, exceptions);
    }

    private static class Method {
      private final int flags;
      // name + descriptor
      private final String signature;

      public Method(int flags, String name, String desc) {
        this.flags = flags;
        this.signature = name + desc;
      }

      public boolean is(int flag) {
        boolean result = (flags & flag) != 0;
        return result;
      }

      public int getFlags() {
        return flags;
      }

      @Override
      public String toString() {
        return new StringBuilder("Unloaded: ").append(signature).toString();
      }

      @Override
      public boolean equals(Object o) {
        if (o instanceof Method) {
          return signature.toString().equals(((Method) o).signature);
        }
        return false;
      }

      @Override
      public int hashCode() {
        return signature.hashCode();
      }
    }
  }
}
