package datadog.trace.agent.tooling.muzzle;

import datadog.trace.agent.tooling.AdviceShader;
import datadog.trace.agent.tooling.advice.AdviceScanResult;
import datadog.trace.agent.tooling.advice.AdviceScanResult.ClassInfo;
import datadog.trace.agent.tooling.advice.AdviceScanResult.HandleUse;
import datadog.trace.agent.tooling.advice.AdviceScanResult.SourceLocation;
import datadog.trace.agent.tooling.advice.AdviceScanResult.Usage;
import datadog.trace.bootstrap.Constants;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;

/** Converts neutral advice uses into the existing member-level muzzle reference model. */
public final class ReferenceCreator {
  private static final Set<String> OBJECT_METHODS = new HashSet<>();

  static {
    for (Method method : Object.class.getMethods()) {
      OBJECT_METHODS.add(methodSig(method.getName(), Type.getMethodDescriptor(method)));
    }
  }

  private final AdviceScanResult scanResult;
  private final AdviceShader shader;
  private final Map<String, Reference> references = new LinkedHashMap<>();
  private final Queue<String> sources = new ArrayDeque<>();
  private final Set<String> visitedSources = new HashSet<>();

  private ReferenceCreator(AdviceScanResult scanResult, AdviceShader shader) {
    this.scanResult = scanResult;
    this.shader = shader;
  }

  static List<Reference> createReferences(AdviceScanResult scanResult, AdviceShader shader) {
    ReferenceCreator creator = new ReferenceCreator(scanResult, shader);
    creator.sources.addAll(scanResult.getAdviceRoots());
    creator.createReferences();
    return new ArrayList<>(creator.references.values());
  }

  private void createReferences() {
    String sourceClass;
    while ((sourceClass = sources.poll()) != null) {
      if (!visitedSources.add(sourceClass)) {
        continue;
      }
      ClassInfo info = scanResult.getClassInfo(sourceClass);
      if (info != null && info.isScanned()) {
        for (Usage usage : info.getUsages()) {
          add(usage);
        }
      }
    }
  }

  private void add(Usage usage) {
    switch (usage.getKind()) {
      case TYPE:
        // ReferenceCreator did not visit MULTIANEWARRAY instructions.
        if (usage.getOpcode() != Opcodes.MULTIANEWARRAY) {
          addTypeReference(usage.getOwner(), usage.getSource(), usage.isImplementedInterface());
        }
        break;
      case FIELD:
        addFieldReference(usage);
        break;
      case METHOD:
        addMethodReference(usage);
        break;
      case HANDLE:
        // ReferenceCreator did not treat method handles loaded with ldc as muzzle references.
        break;
      case INVOKEDYNAMIC:
        addInvokeDynamicReferences(usage.getHandles(), usage.getSource());
        break;
      default:
        throw new IllegalStateException("Unhandled advice usage " + usage.getKind());
    }
  }

  private void addFieldReference(Usage usage) {
    String owner = underlyingClassName(usage.getOwner());
    if (owner == null) {
      return;
    }
    String shadedOwner = shade(owner);
    if (ignoreReference(shadedOwner)) {
      return;
    }
    String source = shade(usage.getSource().getClassName());
    String sourceInternal = internalName(source);
    String ownerInternal = internalName(shadedOwner);
    int fieldFlags = computeMinimumFieldAccess(sourceInternal, ownerInternal);
    fieldFlags |=
        usage.getOpcode() == Opcodes.GETSTATIC || usage.getOpcode() == Opcodes.PUTSTATIC
            ? Reference.EXPECTS_STATIC
            : Reference.EXPECTS_NON_STATIC;
    merge(
        owner,
        new Reference.Builder(ownerInternal)
            .withSource(source, usage.getSource().getLine())
            .withFlag(computeMinimumClassAccess(sourceInternal, ownerInternal))
            .withField(
                new String[] {source + ":" + usage.getSource().getLine()},
                fieldFlags,
                usage.getName(),
                shadeTypeDescriptor(usage.getDescriptor()))
            .build());
    addDescriptorType(Type.getType(usage.getDescriptor()), usage.getSource());
  }

  private void addMethodReference(Usage usage) {
    String owner = underlyingClassName(usage.getOwner());
    if (owner == null) {
      return;
    }
    String shadedOwner = shade(owner);
    String descriptor = shadeMethodDescriptor(usage.getDescriptor());
    if (ignoreReference(shadedOwner) || ignoreObjectMethod(usage.getName(), descriptor)) {
      return;
    }

    Type originalMethodType = Type.getMethodType(usage.getDescriptor());
    addDescriptorType(originalMethodType.getReturnType(), usage.getSource());
    for (Type argument : originalMethodType.getArgumentTypes()) {
      addDescriptorType(argument, usage.getSource());
    }

    String source = shade(usage.getSource().getClassName());
    String sourceInternal = internalName(source);
    String ownerInternal = internalName(shadedOwner);
    int methodFlags = computeMinimumMethodAccess(sourceInternal, ownerInternal);
    methodFlags |=
        usage.getOpcode() == Opcodes.INVOKESTATIC
            ? Reference.EXPECTS_STATIC
            : Reference.EXPECTS_NON_STATIC;
    Type methodType = Type.getMethodType(descriptor);
    merge(
        owner,
        new Reference.Builder(ownerInternal)
            .withSource(source, usage.getSource().getLine())
            .withFlag(
                usage.isInterfaceOwner()
                    ? Reference.EXPECTS_INTERFACE
                    : Reference.EXPECTS_NON_INTERFACE)
            .withFlag(computeMinimumClassAccess(sourceInternal, ownerInternal))
            .withMethod(
                new String[] {source + ":" + usage.getSource().getLine()},
                methodFlags,
                usage.getName(),
                methodType.getReturnType(),
                methodType.getArgumentTypes())
            .build());
  }

  private void addInvokeDynamicReferences(List<HandleUse> handles, SourceLocation source) {
    for (HandleUse handle : handles) {
      String className = handle.getOwner();
      String shadedClass = shade(className);
      if (shadedClass.startsWith("java.")) {
        continue;
      }
      String shadedSource = shade(source.getClassName());
      String sourceInternal = internalName(shadedSource);
      String classInternal = internalName(shadedClass);
      merge(
          className,
          new Reference.Builder(classInternal)
              .withSource(shadedSource, source.getLine())
              .withFlag(computeMinimumClassAccess(sourceInternal, classInternal))
              .build());
    }
  }

  private void addDescriptorType(Type type, SourceLocation source) {
    while (type.getSort() == Type.ARRAY) {
      type = type.getElementType();
    }
    if (type.getSort() == Type.OBJECT) {
      addTypeReference(type.getClassName(), source, false);
    }
  }

  private void addTypeReference(
      String className, SourceLocation source, boolean implementedInterface) {
    String shadedClass = shade(className);
    if (ignoreReference(shadedClass)) {
      return;
    }
    String shadedSource = shade(source.getClassName());
    String sourceInternal = internalName(shadedSource);
    String classInternal = internalName(shadedClass);
    int flags =
        implementedInterface
            ? Reference.EXPECTS_PUBLIC
            : computeMinimumClassAccess(sourceInternal, classInternal);
    merge(
        className,
        new Reference.Builder(classInternal)
            .withSource(shadedSource, source.getLine())
            .withFlag(flags)
            .build());
  }

  private void merge(String unshadedClassName, Reference reference) {
    Reference previous = references.get(reference.className);
    references.put(reference.className, previous == null ? reference : previous.merge(reference));
    ClassInfo info = scanResult.getClassInfo(unshadedClassName);
    if (info != null && info.isScanned() && info.isInstrumentationClass()) {
      sources.add(unshadedClassName);
    }
  }

  private String shade(String className) {
    return shader == null ? className : shader.shadeClassName(className);
  }

  private String shadeTypeDescriptor(String descriptor) {
    return shader == null ? descriptor : shader.shadeTypeDescriptor(descriptor);
  }

  private String shadeMethodDescriptor(String descriptor) {
    return shader == null ? descriptor : shader.shadeMethodDescriptor(descriptor);
  }

  private static int computeMinimumClassAccess(String from, String to) {
    if (from.equalsIgnoreCase(to)) {
      return 0;
    } else if (samePackage(from, to)) {
      return Reference.EXPECTS_NON_PRIVATE;
    } else {
      return Reference.EXPECTS_PUBLIC;
    }
  }

  private static int computeMinimumFieldAccess(String from, String to) {
    if (from.equalsIgnoreCase(to)) {
      return 0;
    } else if (samePackage(from, to)) {
      return Reference.EXPECTS_NON_PRIVATE;
    } else {
      return Reference.EXPECTS_PUBLIC_OR_PROTECTED;
    }
  }

  private static int computeMinimumMethodAccess(String from, String to) {
    return from.equalsIgnoreCase(to) ? 0 : Reference.EXPECTS_PUBLIC_OR_PROTECTED;
  }

  private static boolean samePackage(String from, String to) {
    int fromLength = from.lastIndexOf('/');
    int toLength = to.lastIndexOf('/');
    return fromLength == toLength && from.regionMatches(0, to, 0, fromLength + 1);
  }

  private static boolean ignoreReference(String name) {
    String dottedName = name.replace('/', '.');
    if (dottedName.startsWith("[")) {
      int componentMarker = dottedName.lastIndexOf("[L");
      if (componentMarker < 0) {
        return true;
      }
      dottedName = dottedName.substring(componentMarker + 2);
    }
    if (dottedName.startsWith("java.") || dottedName.startsWith("org.slf4j.")) {
      return true;
    }
    for (String prefix : Constants.BOOTSTRAP_PACKAGE_PREFIXES) {
      if (dottedName.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }

  private static boolean ignoreObjectMethod(String methodName, String methodDescriptor) {
    return OBJECT_METHODS.contains(methodSig(methodName, methodDescriptor));
  }

  private static String methodSig(String methodName, String methodDescriptor) {
    return methodName + methodDescriptor;
  }

  private static String internalName(String className) {
    return className.replace('.', '/');
  }

  private static String underlyingClassName(String className) {
    if (!className.startsWith("[")) {
      return className;
    }
    Type type = Type.getType(internalName(className));
    while (type.getSort() == Type.ARRAY) {
      type = type.getElementType();
    }
    return type.getSort() == Type.OBJECT ? type.getClassName() : null;
  }
}
