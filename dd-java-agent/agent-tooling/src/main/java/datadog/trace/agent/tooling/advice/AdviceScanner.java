package datadog.trace.agent.tooling.advice;

import static datadog.trace.agent.tooling.HelperScanner.isHelperClass;
import static java.util.Collections.addAll;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptySet;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toCollection;

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

  private final InstrumenterModule module;
  private final ClassFileLocator classFileLocator;
  private final Set<String> moduleOutputClasses;
  private final LinkedHashSet<String> adviceRoots = new LinkedHashSet<>();
  private final LinkedHashMap<String, MutableClassInfo> classes = new LinkedHashMap<>();
  private final Deque<String> scanQueue = new ArrayDeque<>();
  private final Set<String> queued = new LinkedHashSet<>();

  private AdviceScanner(InstrumenterModule module, ClassFileLocator classFileLocator) {
    this.module = module;
    this.classFileLocator = classFileLocator;
    moduleOutputClasses = findModuleOutputClasses(module);
  }

  public static AdviceScanResult scan(InstrumenterModule module) {
    return scan(
        module, ClassFileLocator.ForClassLoader.of(Thread.currentThread().getContextClassLoader()));
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

    Map<String, ClassInfo> immutableClasses = new LinkedHashMap<>();
    for (Map.Entry<String, MutableClassInfo> entry : classes.entrySet()) {
      immutableClasses.put(entry.getKey(), entry.getValue().freeze());
    }
    return new AdviceScanResult(adviceRoots, immutableClasses);
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

  private MutableClassInfo discover(String className, String adviceClass) {
    if (className == null) {
      return null;
    }
    MutableClassInfo existing = classes.get(className);
    if (existing != null) {
      if (adviceClass != null && existing.adviceClass == null) {
        existing.adviceClass = adviceClass;
      }
      return existing;
    }
    MutableClassInfo created =
        new MutableClassInfo(className, adviceClass, moduleOutputClasses.contains(className));
    classes.put(className, created);
    return created;
  }

  private void enqueue(MutableClassInfo info, boolean adviceRoot) {
    if (info != null
        && (adviceRoot
            || AdviceScanResult.isInstrumentationClass(info.className)
            || isHelperClass(info.className, info.fromModuleOutput))
        && queued.add(info.className)) {
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
        MutableClassInfo nested = discover(className, owner.adviceClass);
        if (queued.add(className)) {
          scanQueue.addLast(className);
        }
      }
    }
  }

  private void addDependency(MutableClassInfo from, String className) {
    if (className == null || className.equals(from.className)) {
      return;
    }
    if (className.startsWith("[")) {
      addTypeDependency(from, Type.getType(className));
      return;
    }
    from.dependencies.add(className);
    MutableClassInfo target = discover(className, from.adviceClass);
    enqueue(target, false);
  }

  private void addRequiredDependency(MutableClassInfo from, String className) {
    if (className != null && !className.equals(from.className)) {
      from.requiredDependencies.add(className);
      addDependency(from, className);
    }
  }

  private void addTypeDependency(MutableClassInfo from, Type type) {
    if (type == null) {
      return;
    }
    type = underlyingType(type);
    if (type.getSort() == Type.METHOD) {
      for (Type argument : type.getArgumentTypes()) {
        addTypeDependency(from, argument);
      }
      addTypeDependency(from, type.getReturnType());
    } else if (type.getSort() == Type.OBJECT) {
      addDependency(from, type.getClassName());
    }
  }

  private void addHandleDependencies(MutableClassInfo from, Handle handle) {
    addDependency(from, binaryName(handle.getOwner()));
    addTypeDependency(from, Type.getType(handle.getDesc()));
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
          .filter(name -> name.endsWith(".class"))
          .map(name -> name.substring(0, name.length() - ".class".length()))
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
    String resource = info.className.replace('.', '/') + ".class";
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
    return info == null || info.adviceClass == null ? "<none>" : info.adviceClass;
  }

  private IllegalStateException scanFailure(
      String className, String adviceClass, String detail, Throwable cause) {
    String message =
        "Advice scan failed for module "
            + module.getClass().getName()
            + ", advice "
            + adviceClass
            + ", class "
            + className
            + ": "
            + detail;
    return cause == null
        ? new IllegalStateException(message)
        : new IllegalStateException(message, cause);
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
      String sourceClassName,
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
        new SourceLocation(sourceClassName, line),
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

    private ScanningVisitor(MutableClassInfo info) {
      super(Opcodes.ASM7);
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
      if (interfaces != null) {
        for (String interfaceName : interfaces) {
          String binaryInterface = binaryName(interfaceName);
          addRequiredDependency(info, binaryInterface);
          info.usages.add(
              createUsage(
                  info.className,
                  UsageKind.TYPE,
                  UNDEFINED_LINE,
                  -1,
                  binaryInterface,
                  null,
                  null,
                  true,
                  true,
                  emptyList()));
        }
      }
      // Record the hierarchy for helper ordering. Muzzle's superclass reference remains captured
      // by the invokespecial instruction in each constructor.
      if (superName != null) {
        addRequiredDependency(info, binaryName(superName));
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
        addRequiredDependency(info, binaryName(owner));
      }
    }

    @Override
    public FieldVisitor visitField(
        int access, String name, String descriptor, String signature, Object value) {
      addTypeDependency(info, Type.getType(descriptor));
      return null;
    }

    @Override
    public MethodVisitor visitMethod(
        int access, String name, String descriptor, String signature, String[] exceptions) {
      addTypeDependency(info, Type.getMethodType(descriptor));
      if (exceptions != null) {
        for (String exception : exceptions) {
          addDependency(info, binaryName(exception));
        }
      }
      return new ScanningMethodVisitor(info);
    }
  }

  private final class ScanningMethodVisitor extends MethodVisitor {
    private final MutableClassInfo info;
    private int line = UNDEFINED_LINE;

    private ScanningMethodVisitor(MutableClassInfo info) {
      super(Opcodes.ASM7);
      this.info = info;
    }

    @Override
    public void visitLineNumber(int line, Label start) {
      this.line = line;
    }

    @Override
    public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
      if (type != null) {
        addDependency(info, binaryName(type));
      }
    }

    @Override
    public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
      String binaryOwner = binaryName(owner);
      addDependency(info, binaryOwner);
      addTypeDependency(info, Type.getType(descriptor));
      info.usages.add(
          usage(UsageKind.FIELD, opcode, binaryOwner, name, descriptor, false, emptyList()));
    }

    @Override
    public void visitMethodInsn(
        int opcode, String owner, String name, String descriptor, boolean isInterface) {
      String binaryOwner = binaryName(owner);
      addDependency(info, binaryOwner);
      addTypeDependency(info, Type.getMethodType(descriptor));
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
      addTypeDependency(info, Type.getMethodType(descriptor));
      List<HandleUse> handles = new ArrayList<>();
      addHandle(handles, bootstrapMethodHandle);
      for (Object argument : arguments) {
        if (argument instanceof Handle) {
          addHandle(handles, (Handle) argument);
        } else if (argument instanceof Type) {
          addTypeDependency(info, (Type) argument);
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
        addHandleDependencies(info, handle);
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
        addDependency(info, binaryType);
        info.usages.add(
            usage(UsageKind.TYPE, opcode, binaryType, null, descriptor, false, emptyList()));
      }
    }

    private void addHandle(List<HandleUse> handles, Handle handle) {
      addHandleDependencies(info, handle);
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
          info.className,
          kind,
          line,
          opcode,
          owner,
          name,
          descriptor,
          interfaceOwner,
          false,
          handles);
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
    private final String className;
    private final boolean fromModuleOutput;
    private String adviceClass;
    private boolean scanned;
    private boolean reachableFromAdvice;
    private final Set<String> dependencies = new LinkedHashSet<>();
    private final Set<String> requiredDependencies = new LinkedHashSet<>();
    private final List<Usage> usages = new ArrayList<>();

    private MutableClassInfo(String className, String adviceClass, boolean fromModuleOutput) {
      this.className = className;
      this.adviceClass = adviceClass;
      this.fromModuleOutput = fromModuleOutput;
    }

    private ClassInfo freeze() {
      return new ClassInfo(
          className, fromModuleOutput, scanned, reachableFromAdvice, requiredDependencies, usages);
    }
  }
}
