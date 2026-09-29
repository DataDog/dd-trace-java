package datadog.trace.agent.tooling.advice;

import static datadog.trace.agent.tooling.HelperScanner.isHelperClass;
import static java.util.Collections.addAll;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptySet;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toCollection;
import static net.bytebuddy.utility.OpenedClassReader.ASM_API;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.advice.AdviceScanResult.ClassInfo;
import datadog.trace.agent.tooling.advice.AdviceScanResult.HandleUse;
import datadog.trace.agent.tooling.advice.AdviceScanResult.SourceLocation;
import datadog.trace.agent.tooling.advice.AdviceScanResult.Usage;
import datadog.trace.agent.tooling.advice.AdviceScanResult.UsageKind;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.FieldVisitor;
import net.bytebuddy.jar.asm.Handle;
import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;

/** Scans all advice and reachable instrumentation bytecode for one instrumenter module. */
public final class AdviceScanner {
  private static final int UNDEFINED_LINE = -1;
  private static final String CLASS_FILE_SUFFIX = ".class";

  private final InstrumenterModule module;
  private final ClassFileLocator classFileLocator;
  private final Set<String> moduleOutputClasses;
  private final LinkedHashSet<String> adviceRoots = new LinkedHashSet<>();
  private final LinkedHashMap<String, MutableClassInfo> classes = new LinkedHashMap<>();
  private final Deque<String> scanQueue = new ArrayDeque<>();
  private final Set<String> visited = new HashSet<>();

  private AdviceScanner(InstrumenterModule module, ClassFileLocator classFileLocator) {
    this.module = module;
    this.classFileLocator = classFileLocator;
    moduleOutputClasses = findModuleOutputClasses(module);
  }

  public static AdviceScanResult scan(InstrumenterModule module) {
    return scan(module, Thread.currentThread().getContextClassLoader());
  }

  public static AdviceScanResult scan(InstrumenterModule module, ClassLoader classLoader) {
    return scan(module, ClassFileLocator.ForClassLoader.of(classLoader));
  }

  static AdviceScanResult scan(InstrumenterModule module, ClassFileLocator classFileLocator) {
    return new AdviceScanner(module, classFileLocator).scan();
  }

  private AdviceScanResult scan() {
    collectAdviceRoots();
    for (String adviceRoot : adviceRoots) {
      MutableClassInfo info = discover(adviceRoot, adviceRoot);
      enqueue(info, true);
    }

    String className;
    while ((className = scanQueue.pollFirst()) != null) {
      scanClass(classes.get(className));
    }
    markReachableClasses();

    Map<String, ClassInfo> frozenClasses = new LinkedHashMap<>();
    for (Map.Entry<String, MutableClassInfo> entry : classes.entrySet()) {
      frozenClasses.put(entry.getKey(), entry.getValue().freeze());
    }
    return new AdviceScanResult(adviceRoots, frozenClasses);
  }

  private void markReachableClasses() {
    Deque<String> pending = new ArrayDeque<>(adviceRoots);
    String className;
    while ((className = pending.pollFirst()) != null) {
      MutableClassInfo info = classes.get(className);
      if (info != null && !info.reachableFromAdvice) {
        info.reachableFromAdvice = true;
        pending.addAll(info.dependencies);
      }
    }
  }

  private void collectAdviceRoots() {
    List<Instrumenter> instrumenters = module.typeInstrumentations();
    for (Instrumenter instrumenter : instrumenters) {
      if (instrumenter instanceof Instrumenter.HasMethodAdvice) {
        ((Instrumenter.HasMethodAdvice) instrumenter)
            .methodAdvice(
                (matcher, adviceClass, additionalClasses) -> {
                  adviceRoots.add(adviceClass);
                  if (additionalClasses != null) {
                    addAll(adviceRoots, additionalClasses);
                  }
                });
      }
    }
  }

  private MutableClassInfo discover(String className, String adviceRoot) {
    if (className == null) {
      return null;
    }
    MutableClassInfo existing = classes.get(className);
    if (existing != null) {
      if (adviceRoot != null && existing.adviceRoot == null) {
        existing.adviceRoot = adviceRoot;
      }
      return existing;
    }
    MutableClassInfo created =
        new MutableClassInfo(className, adviceRoot, moduleOutputClasses.contains(className));
    classes.put(className, created);
    return created;
  }

  private void enqueue(MutableClassInfo info, boolean adviceRoot) {
    if (info == null) {
      return;
    }
    if ((adviceRoot
            || AdviceScanResult.isInstrumentationClass(info.className)
            || isHelperClass(info.className, info.fromModuleOutput))
        && visited.add(info.className)) {
      scanQueue.addLast(info.className);
      if (!adviceRoot && info.fromModuleOutput) {
        enqueueNestedClasses(info);
      }
    }
  }

  private void enqueueNestedClasses(MutableClassInfo owner) {
    String prefix = owner.className + "$";
    for (String className : moduleOutputClasses) {
      if (className.startsWith(prefix)) {
        MutableClassInfo nested = discover(className, owner.adviceRoot);
        if (visited.add(className)) {
          scanQueue.addLast(className);
        }
      }
    }
  }

  private void addDependency(MutableClassInfo from, String className) {
    addDependency(from, className, true);
  }

  private void addDependency(MutableClassInfo from, String className, boolean trackReachability) {
    if (className == null || className.equals(from.className)) {
      return;
    }
    if (className.startsWith("[")) {
      addTypeDependency(from, Type.getType(className.substring(1)), trackReachability);
      return;
    }
    if (trackReachability) {
      from.dependencies.add(className);
    }
    MutableClassInfo target = discover(className, from.adviceRoot);
    // Muzzle also needs bytecode excluded from helper reachability, such as advice superclasses.
    enqueue(target, false);
  }

  private void addRequiredDependency(MutableClassInfo from, String className) {
    if (className != null && !className.equals(from.className)) {
      from.requiredDependencies.add(className);
      addDependency(from, className);
    }
  }

  private void addTypeDependency(MutableClassInfo from, Type type, boolean trackReachability) {
    if (type == null) {
      return;
    }
    type = underlyingType(type);
    if (type.getSort() == Type.METHOD) {
      for (Type argument : type.getArgumentTypes()) {
        addTypeDependency(from, argument, trackReachability);
      }
      addTypeDependency(from, type.getReturnType(), trackReachability);
    } else if (type.getSort() == Type.OBJECT) {
      addDependency(from, type.getClassName(), trackReachability);
    }
  }

  private void addHandleDependencies(
      MutableClassInfo from, Handle handle, boolean trackReachability) {
    addDependency(from, binaryName(handle.getOwner()), trackReachability);
    addTypeDependency(from, Type.getType(handle.getDesc()), trackReachability);
  }

  private static Set<String> findModuleOutputClasses(InstrumenterModule module) {
    CodeSource codeSource = module.getClass().getProtectionDomain().getCodeSource();
    if (codeSource == null || codeSource.getLocation() == null) {
      throw new IllegalStateException(
          "Cannot locate compiled output for " + module.getClass().getName());
    }
    Path root;
    try {
      root = Paths.get(codeSource.getLocation().toURI());
    } catch (URISyntaxException error) {
      throw new IllegalStateException(
          "Cannot resolve compiled output for " + module.getClass().getName(), error);
    }
    if (!Files.isDirectory(root)) {
      return emptySet();
    }
    try (Stream<Path> files = Files.walk(root)) {
      return files
          .filter(Files::isRegularFile)
          .map(root::relativize)
          .map(Path::toString)
          .filter(name -> name.endsWith(CLASS_FILE_SUFFIX))
          .map(name -> name.substring(0, name.length() - CLASS_FILE_SUFFIX.length()))
          .map(name -> name.replace('/', '.').replace('\\', '.'))
          .sorted()
          .collect(toCollection(LinkedHashSet::new));
    } catch (IOException error) {
      throw new IllegalStateException(
          "Cannot inspect compiled output for " + module.getClass().getName(), error);
    }
  }

  @SuppressForbidden
  private void scanClass(MutableClassInfo info) {
    String resource = info.className.replace('.', '/') + CLASS_FILE_SUFFIX;
    ClassFileLocator.Resolution resolution;
    try {
      resolution = classFileLocator.locate(info.className);
    } catch (Throwable error) {
      throw scanFailure(
          info.className, owningAdvice(info.className), "cannot read class bytecode", error);
    }
    if (!resolution.isResolved()) {
      if (adviceRoots.contains(info.className) || info.fromModuleOutput) {
        throw scanFailure(
            info.className,
            owningAdvice(info.className),
            adviceRoots.contains(info.className)
                ? "advice class is missing"
                : "helper class from module output is missing",
            null);
      }
      System.err.println(resource + " not found, skipping");
      return;
    }
    try {
      new ClassReader(resolution.resolve())
          .accept(new ScanningVisitor(info), ClassReader.SKIP_FRAMES);
      info.scanned = true;
    } catch (Throwable error) {
      throw scanFailure(
          info.className, owningAdvice(info.className), "cannot parse class bytecode", error);
    }
  }

  private String owningAdvice(String className) {
    MutableClassInfo info = classes.get(className);
    return info == null || info.adviceRoot == null ? "<none>" : info.adviceRoot;
  }

  private IllegalStateException scanFailure(
      String className, String adviceRoot, String detail, Throwable cause) {
    String message =
        "Advice scan failed for module "
            + module.getClass().getName()
            + ", advice "
            + adviceRoot
            + ", class "
            + className
            + ": "
            + detail;
    return new IllegalStateException(message, cause);
  }

  private static String binaryName(String internalName) {
    return internalName.replace('/', '.');
  }

  private static Type underlyingType(Type type) {
    while (type.getSort() == Type.ARRAY) {
      type = type.getElementType();
    }
    return type;
  }

  private static Usage createUsage(
      MutableClassInfo source,
      UsageKind kind,
      int line,
      int opcode,
      String owner,
      String name,
      String descriptor,
      boolean interfaceOwner,
      boolean implementedInterface,
      List<HandleUse> handles) {
    return new Usage(
        kind,
        new SourceLocation(source.className, source.sourceFile, line),
        opcode,
        owner,
        name,
        descriptor,
        interfaceOwner,
        implementedInterface,
        handles);
  }

  private final class ScanningVisitor extends ClassVisitor {
    private final MutableClassInfo info;
    private String[] interfaces;

    private ScanningVisitor(MutableClassInfo info) {
      super(ASM_API);
      this.info = info;
    }

    @Override
    public void visit(
        int version,
        int access,
        String name,
        String signature,
        String superName,
        String[] interfaces) {
      this.interfaces = interfaces;
      if (interfaces != null) {
        for (String interfaceName : interfaces) {
          addHierarchyDependency(interfaceName);
        }
      }
      // Record the hierarchy for helper ordering. Muzzle's superclass reference remains captured
      // by the invokespecial instruction in each constructor.
      if (superName != null) {
        addHierarchyDependency(superName);
      }
    }

    private void addHierarchyDependency(String internalName) {
      String className = binaryName(internalName);
      if (adviceRoots.contains(info.className)) {
        addDependency(info, className, false);
      } else {
        addRequiredDependency(info, className);
      }
    }

    @Override
    public void visitSource(String source, String debug) {
      if (source != null) {
        info.sourceFile = source;
      }
    }

    @Override
    public void visitEnd() {
      // SourceFile follows the class header. Prepend header usages once its filename is known.
      if (interfaces != null) {
        int index = 0;
        for (String interfaceName : interfaces) {
          info.usages.add(
              index++,
              createUsage(
                  info,
                  UsageKind.TYPE,
                  UNDEFINED_LINE,
                  -1,
                  binaryName(interfaceName),
                  null,
                  null,
                  true,
                  true,
                  emptyList()));
        }
      }
    }

    @Override
    public void visitInnerClass(String name, String outerName, String innerName, int access) {
      if (outerName != null && info.className.equals(binaryName(name))) {
        addEnclosingClass(outerName);
      }
    }

    @Override
    public void visitOuterClass(String owner, String name, String descriptor) {
      addEnclosingClass(owner);
    }

    private void addEnclosingClass(String owner) {
      // Advice is inlined; its enclosing instrumentation class does not need injection.
      if (!adviceRoots.contains(info.className)) {
        String enclosingClass = binaryName(owner);
        // Enclosure affects ordering, but does not itself make the owner an injectable helper.
        info.requiredDependencies.add(enclosingClass);
        addDependency(info, enclosingClass, false);
      }
    }

    @Override
    public FieldVisitor visitField(
        int access, String name, String descriptor, String signature, Object value) {
      addTypeDependency(info, Type.getType(descriptor), !adviceRoots.contains(info.className));
      return null;
    }

    @Override
    public MethodVisitor visitMethod(
        int access, String name, String descriptor, String signature, String[] exceptions) {
      boolean adviceRoot = adviceRoots.contains(info.className);
      addTypeDependency(info, Type.getMethodType(descriptor), !adviceRoot);
      if (exceptions != null) {
        for (String exception : exceptions) {
          addDependency(info, binaryName(exception), !adviceRoot);
        }
      }
      return new ScanningMethodVisitor(info, !adviceRoot || !"<init>".equals(name));
    }
  }

  private final class ScanningMethodVisitor extends MethodVisitor {
    private final MutableClassInfo info;
    private final boolean trackReachability;
    private int line = UNDEFINED_LINE;

    private ScanningMethodVisitor(MutableClassInfo info, boolean trackReachability) {
      super(ASM_API);
      this.info = info;
      this.trackReachability = trackReachability;
    }

    @Override
    public void visitLineNumber(int line, Label start) {
      this.line = line;
    }

    @Override
    public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
      if (type != null) {
        addDependency(info, binaryName(type), trackReachability);
      }
    }

    @Override
    public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
      String binaryOwner = binaryName(owner);
      addDependency(info, binaryOwner, trackReachability);
      addTypeDependency(info, Type.getType(descriptor), trackReachability);
      info.usages.add(
          usage(UsageKind.FIELD, opcode, binaryOwner, name, descriptor, false, emptyList()));
    }

    @Override
    public void visitMethodInsn(
        int opcode, String owner, String name, String descriptor, boolean isInterface) {
      String binaryOwner = binaryName(owner);
      addDependency(info, binaryOwner, trackReachability);
      addTypeDependency(info, Type.getMethodType(descriptor), trackReachability);
      info.usages.add(
          usage(UsageKind.METHOD, opcode, binaryOwner, name, descriptor, isInterface, emptyList()));
    }

    @Override
    public void visitTypeInsn(int opcode, String typeName) {
      addTypeUsage(Type.getObjectType(typeName), opcode, null);
    }

    @Override
    public void visitMultiANewArrayInsn(String descriptor, int dimensions) {
      addTypeUsage(Type.getType(descriptor), Opcodes.MULTIANEWARRAY, descriptor);
    }

    @Override
    public void visitInvokeDynamicInsn(
        String name, String descriptor, Handle bootstrapMethodHandle, Object... arguments) {
      addTypeDependency(info, Type.getMethodType(descriptor), trackReachability);
      List<HandleUse> handles = new ArrayList<>();
      addHandle(handles, bootstrapMethodHandle);
      for (Object argument : arguments) {
        if (argument instanceof Handle) {
          addHandle(handles, (Handle) argument);
        } else if (argument instanceof Type) {
          addTypeDependency(info, (Type) argument, trackReachability);
        }
      }
      info.usages.add(
          usage(
              UsageKind.INVOKEDYNAMIC,
              Opcodes.INVOKEDYNAMIC,
              null,
              name,
              descriptor,
              false,
              handles));
    }

    @Override
    public void visitLdcInsn(Object value) {
      if (value instanceof Type) {
        addTypeUsage((Type) value, Opcodes.LDC, null);
      } else if (value instanceof Handle) {
        Handle handle = (Handle) value;
        addHandleDependencies(info, handle, trackReachability);
        info.usages.add(
            usage(
                UsageKind.HANDLE,
                Opcodes.LDC,
                binaryName(handle.getOwner()),
                handle.getName(),
                handle.getDesc(),
                handle.isInterface(),
                singletonList(toHandleUse(handle))));
      }
    }

    private void addTypeUsage(Type type, int opcode, String descriptor) {
      type = underlyingType(type);
      if (type.getSort() == Type.OBJECT) {
        String binaryType = type.getClassName();
        addDependency(info, binaryType, trackReachability);
        info.usages.add(
            usage(UsageKind.TYPE, opcode, binaryType, null, descriptor, false, emptyList()));
      }
    }

    private void addHandle(List<HandleUse> handles, Handle handle) {
      addHandleDependencies(info, handle, trackReachability);
      handles.add(toHandleUse(handle));
    }

    private Usage usage(
        UsageKind kind,
        int opcode,
        String owner,
        String name,
        String descriptor,
        boolean interfaceOwner,
        List<HandleUse> handles) {
      return createUsage(
          info, kind, line, opcode, owner, name, descriptor, interfaceOwner, false, handles);
    }
  }

  private static HandleUse toHandleUse(Handle handle) {
    return new HandleUse(
        handle.getTag(),
        binaryName(handle.getOwner()),
        handle.getName(),
        handle.getDesc(),
        handle.isInterface());
  }

  private static final class MutableClassInfo {
    final String className;
    final boolean fromModuleOutput;
    String adviceRoot;
    String sourceFile;
    boolean scanned;
    boolean reachableFromAdvice;
    final Set<String> dependencies = new LinkedHashSet<>();
    final Set<String> requiredDependencies = new LinkedHashSet<>();
    final List<Usage> usages = new ArrayList<>();

    MutableClassInfo(String className, String adviceRoot, boolean fromModuleOutput) {
      this.className = className;
      this.fromModuleOutput = fromModuleOutput;
      this.adviceRoot = adviceRoot;
      this.sourceFile = className;
    }

    ClassInfo freeze() {
      return new ClassInfo(
          className, fromModuleOutput, scanned, reachableFromAdvice, requiredDependencies, usages);
    }
  }
}
