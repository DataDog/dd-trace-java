package datadog.trace.agent.tooling.advice;

import static java.util.Collections.addAll;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.advice.AdviceScanResult.ClassInfo;
import datadog.trace.agent.tooling.advice.AdviceScanResult.HandleUse;
import datadog.trace.agent.tooling.advice.AdviceScanResult.SourceLocation;
import datadog.trace.agent.tooling.advice.AdviceScanResult.Usage;
import datadog.trace.agent.tooling.advice.AdviceScanResult.UsageKind;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.security.CodeSource;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.Handle;
import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;
import net.bytebuddy.utility.StreamDrainer;

/** Scans all advice and reachable module bytecode for one instrumenter module. */
public final class AdviceScanner {
  private static final int UNDEFINED_LINE = -1;

  private final InstrumenterModule module;
  private final File ownOutput;
  private final ClassLoader loader;
  private final LinkedHashSet<String> adviceRoots = new LinkedHashSet<>();
  private final LinkedHashMap<String, MutableClassInfo> classes = new LinkedHashMap<>();
  private final Deque<String> scanQueue = new ArrayDeque<>();
  private final Set<String> queued = new LinkedHashSet<>();

  private AdviceScanner(InstrumenterModule module, File ownOutput, ClassLoader loader) {
    this.module = module;
    this.ownOutput = ownOutput;
    this.loader = loader;
  }

  public static AdviceScanResult scan(InstrumenterModule module) {
    return scan(module, sourceRootFor(module), Thread.currentThread().getContextClassLoader());
  }

  static AdviceScanResult scan(InstrumenterModule module, File ownOutput, ClassLoader loader) {
    return new AdviceScanner(module, ownOutput, loader).scan();
  }

  private AdviceScanResult scan() {
    collectAdviceRoots();
    for (String adviceRoot : adviceRoots) {
      MutableClassInfo info = discover(adviceRoot, adviceRoot);
      enqueue(info, true);
    }

    String className;
    while ((className = scanQueue.pollFirst()) != null) {
      MutableClassInfo info = classes.get(className);
      if (info.scanned) {
        continue;
      }
      byte[] bytecode = readClass(info);
      try {
        new ClassReader(bytecode).accept(new ScanningVisitor(info), ClassReader.SKIP_FRAMES);
        info.scanned = true;
      } catch (Throwable error) {
        throw scanFailure(className, owningAdvice(className), "cannot parse class bytecode", error);
      }
    }

    Map<String, ClassInfo> immutableClasses = new LinkedHashMap<>();
    for (Map.Entry<String, MutableClassInfo> entry : classes.entrySet()) {
      immutableClasses.put(entry.getKey(), entry.getValue().freeze());
    }
    return new AdviceScanResult(adviceRoots, immutableClasses);
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
    MutableClassInfo created = new MutableClassInfo(className, isOwnOutput(className), adviceClass);
    classes.put(className, created);
    return created;
  }

  private void enqueue(MutableClassInfo info, boolean adviceRoot) {
    if (info != null && !info.scanned && (adviceRoot || info.owned) && queued.add(info.className)) {
      scanQueue.addLast(info.className);
    }
  }

  private void addDependency(MutableClassInfo from, String className) {
    if (className == null || className.equals(from.className)) {
      return;
    }
    MutableClassInfo target = discover(className, from.adviceClass);
    enqueue(target, false);
  }

  private void addTypeDependency(MutableClassInfo from, Type type) {
    if (type == null) {
      return;
    }
    while (type.getSort() == Type.ARRAY) {
      type = type.getElementType();
    }
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
  }

  private byte[] readClass(MutableClassInfo info) {
    try {
      if (info.owned) {
        File classFile = classFile(info.className);
        if (!classFile.isFile()) {
          throw scanFailure(
              info.className, owningAdvice(info.className), "owned class file is missing", null);
        }
        return java.nio.file.Files.readAllBytes(classFile.toPath());
      }
      String resource = info.className.replace('.', '/') + ".class";
      try (InputStream input = loader.getResourceAsStream(resource)) {
        if (input == null) {
          throw scanFailure(
              info.className, owningAdvice(info.className), "advice class is missing", null);
        }
        return StreamDrainer.DEFAULT.drain(input);
      }
    } catch (IOException error) {
      throw scanFailure(
          info.className, owningAdvice(info.className), "cannot read class bytecode", error);
    }
  }

  private boolean isOwnOutput(String className) {
    return classFile(className).isFile();
  }

  private File classFile(String className) {
    return new File(ownOutput, className.replace('.', File.separatorChar) + ".class");
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

  @SuppressForbidden
  private static File sourceRootFor(InstrumenterModule module) {
    CodeSource codeSource = module.getClass().getProtectionDomain().getCodeSource();
    if (codeSource == null || codeSource.getLocation() == null) {
      throw new IllegalStateException(
          "Cannot locate compiled output for module " + module.getClass().getName());
    }
    try {
      return new File(codeSource.getLocation().toURI());
    } catch (URISyntaxException error) {
      throw new IllegalStateException(
          "Cannot resolve compiled output for module " + module.getClass().getName(), error);
    }
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
          addDependency(info, binaryInterface);
          info.usages.add(
              usage(
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
    }

    @Override
    public MethodVisitor visitMethod(
        int access, String name, String descriptor, String signature, String[] exceptions) {
      return new ScanningMethodVisitor(info);
    }

    private Usage usage(
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
          new SourceLocation(info.className, line),
          opcode,
          owner,
          name,
          descriptor,
          interfaceOwner,
          implementedInterface,
          handles);
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
      Type type = underlyingType(Type.getObjectType(typeName));
      if (type.getSort() == Type.OBJECT) {
        String binaryType = type.getClassName();
        addDependency(info, binaryType);
        info.usages.add(usage(UsageKind.TYPE, opcode, binaryType, null, null, false, emptyList()));
      }
    }

    @Override
    public void visitMultiANewArrayInsn(String descriptor, int dimensions) {
      Type type = underlyingType(Type.getType(descriptor));
      if (type.getSort() == Type.OBJECT) {
        String binaryType = type.getClassName();
        addDependency(info, binaryType);
        info.usages.add(
            usage(
                UsageKind.TYPE,
                Opcodes.MULTIANEWARRAY,
                binaryType,
                null,
                descriptor,
                false,
                emptyList()));
      }
    }

    @Override
    public void visitInvokeDynamicInsn(
        String name, String descriptor, Handle bootstrapMethodHandle, Object... arguments) {
      List<HandleUse> handles = new ArrayList<>();
      addHandle(info, handles, bootstrapMethodHandle);
      for (Object argument : arguments) {
        if (argument instanceof Handle) {
          addHandle(info, handles, (Handle) argument);
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
        Type type = underlyingType((Type) value);
        if (type.getSort() == Type.OBJECT) {
          String binaryType = type.getClassName();
          addDependency(info, binaryType);
          info.usages.add(
              usage(UsageKind.TYPE, Opcodes.LDC, binaryType, null, null, false, emptyList()));
        }
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

    private void addHandle(MutableClassInfo info, List<HandleUse> handles, Handle handle) {
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
      return new Usage(
          kind,
          new SourceLocation(info.className, line),
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
    private final boolean owned;
    private String adviceClass;
    private boolean scanned;
    private final List<Usage> usages = new ArrayList<>();

    private MutableClassInfo(String className, boolean owned, String adviceClass) {
      this.className = className;
      this.owned = owned;
      this.adviceClass = adviceClass;
    }

    private ClassInfo freeze() {
      return new ClassInfo(className, scanned, usages);
    }
  }
}
