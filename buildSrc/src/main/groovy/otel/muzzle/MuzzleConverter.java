package otel.muzzle;

import static java.util.stream.Collectors.toList;
import static org.objectweb.asm.Opcodes.AASTORE;
import static org.objectweb.asm.Opcodes.ANEWARRAY;
import static org.objectweb.asm.Opcodes.ARETURN;
import static org.objectweb.asm.Opcodes.ASM9;
import static org.objectweb.asm.Opcodes.BIPUSH;
import static org.objectweb.asm.Opcodes.DUP;
import static org.objectweb.asm.Opcodes.GETSTATIC;
import static org.objectweb.asm.tree.AbstractInsnNode.FIELD_INSN;
import static org.objectweb.asm.tree.AbstractInsnNode.LDC_INSN;
import static org.objectweb.asm.tree.AbstractInsnNode.METHOD_INSN;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.StreamSupport;

/**
 * This visitor use the ASM Tree API to converts muzzle methods.
 */
public class MuzzleConverter extends ClassVisitor {
  private static final String INSTRUMENTATION_MODULE_MUZZLE_CLASS_NAME =
      "io/opentelemetry/javaagent/tooling/muzzle/InstrumentationModuleMuzzle";
  private static final String STRING_CLASS_NAME = "java/lang/String";
  /* OTel muzzle API */
  private static final String GET_MUZZLE_HELPER_CLASS_NAMES_METHOD_NAME = "getMuzzleHelperClassNames";
  private static final String GET_MUZZLE_HELPS_CASS_NAMES_DESC = "()Ljava/util/List;";
  /* Datadog muzzle API */
  private static final String HELPER_CLASS_NAMES_METHOD_NAME = "helperClassNames";
  private static final String HELPER_CLASS_NAME_DESC = "()[Ljava/lang/String;";
  private static final String ADD_METHOD_NAME = "add";
  private static final String ADD_METHOD_DESC = "(Ljava/lang/Object;)Z";
  private static final String GET_MUZZLE_REFERENCES_METHOD_NAME = "getMuzzleReferences";
  private static final String GET_MUZZLE_REFERENCES_DESC = "()Ljava/util/Map;";
  private static final String REGISTER_MUZZLE_VIRTUAL_FIELDS_METHOD_NAME = "registerMuzzleVirtualFields";
  private static final String REGISTER_MUZZLE_VIRTUAL_FIELDS_DESC =
      "(Lio/opentelemetry/javaagent/tooling/muzzle/VirtualFieldMappingsBuilder;)V";

  private final ClassVisitor next;
  private final String className;
  private final List<MuzzleReference> references;
  private boolean instrumentationModule;

  public MuzzleConverter(ClassVisitor classVisitor, String className) {
    super(ASM9, new ClassNode());
    this.next = classVisitor;
    this.className = className;
    this.references = new ArrayList<>();
    this.instrumentationModule = false;
  }

  @Override
  public void visitEnd() {
    if (inheritsInstrumentationModuleMuzzle()) {
      this.instrumentationModule = true;
      convertHelperClassNames();
      captureMuzzleReferences();
      // TODO Add oll other muzzle methods conversion too
      cleanUpOTelMuzzle();
    } else {
      this.instrumentationModule = false;
    }

    if (this.next != null) {
      ClassNode cn = (ClassNode) cv;
      cn.accept(this.next);
    }
  }

  /**
   * Checks whether the last visited class is an InstrumentationGroup instance.
   *
   * @return {@code true} if the last visited class is an InstrumentationGroup instance, {@code false} otherwise.
   */
  public boolean isInstrumentationModule() {
    return this.instrumentationModule;
  }

  private boolean inheritsInstrumentationModuleMuzzle() {
    ClassNode classNode = (ClassNode) this.cv;
    return classNode.interfaces.stream().anyMatch(INSTRUMENTATION_MODULE_MUZZLE_CLASS_NAME::equals);
  }

  /**
   * Converts OTel {@code public List getMuzzleHelperClassNames()} method into Datadog {@code public String[] helperClassNames()} method.
   */
  private void convertHelperClassNames() {
    ClassNode classNode = (ClassNode) this.cv;
    // Look for OTel method
    MethodNode methodNode = findMethodNode(classNode, GET_MUZZLE_HELPER_CLASS_NAMES_METHOD_NAME, GET_MUZZLE_HELPS_CASS_NAMES_DESC);
    List<String> helperNames = captureHelperClassNames(methodNode);
    /*
     * Update method signature and implementation
     */
    methodNode.name = HELPER_CLASS_NAMES_METHOD_NAME;
    methodNode.desc = HELPER_CLASS_NAME_DESC;
    methodNode.instructions = buildHelperClassNamesInstructions(helperNames);
  }

  /**
   * Captures all the helper names from OTel {@code public List getMuzzleHelperClassNames()} method.
   * @param methodNode The OTel getMuzzleHelperClassNames method.
   * @return The list of helper names.
   */
  private List<String> captureHelperClassNames(MethodNode methodNode) {
    return StreamSupport.stream(methodNode.instructions.spliterator(), false)
        .filter(node -> node.getType() == METHOD_INSN) // Filter method instructions
        .map(node -> (MethodInsnNode) node)
        .filter(node ->
            ADD_METHOD_NAME.equals(node.name) && ADD_METHOD_DESC.equals(node.desc)
                && node.getPrevious() instanceof LdcInsnNode
        ) // Filter add(Object)
        .map(node -> ((LdcInsnNode) node.getPrevious())) // Get previous LDC value as String
        .map(node -> (String) node.cst)
        .collect(toList());
  }

  /**
   * Creates the Datadog {@code public String[] helperClassNames()} method instructions.
   * @param helperNames The helper names.
   * @return The Datadog helperClassNames method instruction list.
   */
  private InsnList buildHelperClassNamesInstructions(List<String> helperNames) {
    InsnList list = new InsnList();
    // Create a String array of the right size
    list.add(new IntInsnNode(BIPUSH, helperNames.size()));
    list.add(new TypeInsnNode(ANEWARRAY, STRING_CLASS_NAME));
    // Append each helper name
    int index = 0;
    for (String helperName : helperNames) {
      list.add(new InsnNode(DUP));
      list.add(new IntInsnNode(BIPUSH, index++));
      list.add(new LdcInsnNode(helperName));
      list.add(new InsnNode(AASTORE));
    }
    // Return the array
    list.add(new InsnNode(ARETURN));
    return list;
  }

  /**
   * Gets the captured muzzle references.
   *
   * @return The captured muzzle references, an empty collection otherwise.
   */
  public List<MuzzleReference> getReferences() {
    return this.references;
  }

  /**
   * Captures OpenTelemetry ClassRef/ClassRefBuilder/Source/Field/Method and ASM Type calls to recreate muzzle references.
   */
  private void captureMuzzleReferences() {
    ClassNode classNode = (ClassNode) this.cv;
    // Look for OTel method
    MethodNode methodNode = findMethodNode(classNode, GET_MUZZLE_REFERENCES_METHOD_NAME, GET_MUZZLE_REFERENCES_DESC);
    // Store sources, flags and types captured along walking the instructions
    List<String> sources = new ArrayList<>();
    int flags = 0;
    List<String> types = new ArrayList<>();
    // Declare the muzzle reference to be consolidated when walking over OTel builder API
    MuzzleReference currentReference = null;
    for (final AbstractInsnNode instructionNode : methodNode.instructions) {
      if (instructionNode.getType() == METHOD_INSN) {
        MethodInsnNode methodInsnNode = (MethodInsnNode) instructionNode;
        // Look for ClassRef.builder(String)
        if ("io/opentelemetry/javaagent/tooling/muzzle/references/ClassRef".equals(methodInsnNode.owner)
            && "builder".equals(methodInsnNode.name)
            && "(Ljava/lang/String;)Lio/opentelemetry/javaagent/tooling/muzzle/references/ClassRefBuilder;".equals(methodInsnNode.desc)
            && methodInsnNode.getPrevious().getType() == LDC_INSN) {
          LdcInsnNode ldcInsnNode = (LdcInsnNode) methodInsnNode.getPrevious();
          if (currentReference != null) {
            this.references.add(currentReference);
          }
          currentReference = new MuzzleReference();
          currentReference.className = ((String) ldcInsnNode.cst);
        } else if ("io/opentelemetry/javaagent/tooling/muzzle/references/ClassRefBuilder".equals(methodInsnNode.owner)) {
          if (currentReference == null) {
            throw new IllegalStateException("Failed to capture muzzle references of " + className + ": no current reference");
          }
          // Look for ClassRefBuilder.setSuperClassName(String)
          if ("setSuperClassName".equals(methodInsnNode.name)
              && "(Ljava/lang/String;)Lio/opentelemetry/javaagent/tooling/muzzle/references/ClassRefBuilder;".equals(methodInsnNode.desc)
              && methodInsnNode.getPrevious().getType() == LDC_INSN) {
            currentReference.superName = ((String) ((LdcInsnNode) methodInsnNode.getPrevious()).cst);
          }
          // Look for ClassRefBuilder.addSource(String, int)
          // NOTE: Call for ClassRefBuilder.addSource(String) will be forwarded to ClassRefBuilder.addSource(String, int)
          else if ("addSource".equals(methodInsnNode.name)
              && "(Ljava/lang/String;I)Lio/opentelemetry/javaagent/tooling/muzzle/references/ClassRefBuilder;".equals(methodInsnNode.desc)
              && methodInsnNode.getPrevious().getType() == LDC_INSN
              && methodInsnNode.getPrevious().getPrevious().getType() == LDC_INSN) {
            String source = ((LdcInsnNode) methodInsnNode.getPrevious().getPrevious()).cst + ":" + ((LdcInsnNode) methodInsnNode.getPrevious()).cst;
            currentReference.sources.add(source);
          }
          // Look for ClassRefBuilder.addFlag(Flag)
          else if ("addFlag".equals(methodInsnNode.name)
              && "(Lio/opentelemetry/javaagent/tooling/muzzle/references/Flag;)Lio/opentelemetry/javaagent/tooling/muzzle/references/ClassRefBuilder;".equals(methodInsnNode.desc)) {
            currentReference.flags += flags;
            flags = 0;
          }
          // Look for ClassRefBuilder.addInterfaceName(String)
          else if ("addInterfaceName".equals(methodInsnNode.name)
              && "(Ljava/lang/String;)Lio/opentelemetry/javaagent/tooling/muzzle/references/ClassRefBuilder;".equals(methodInsnNode.desc)
              && methodInsnNode.getPrevious().getType() == LDC_INSN) {
            currentReference.interfaces.add((String) ((LdcInsnNode) methodInsnNode.getPrevious()).cst);
          }
          // Look for ClassRefBuilder.addInterfaceName(Collection<String>). It is not supported as it does not seem to be used
          else if ("addInterfaceNames".equals(methodInsnNode.name)
              && "(Ljava/util/Collection;)Lio/opentelemetry/javaagent/tooling/muzzle/references/ClassRefBuilder;".equals(methodInsnNode.desc)) {
            throw new IllegalStateException("ClassRefBuilder.addInterfaceName(Collection<String>) is not supported");
          }
          // Look for ClassRefBuilder.addField(Source[], Flag[], String, Type, boolean)
          else if ("addField".equals(methodInsnNode.name)
          && "([Lio/opentelemetry/javaagent/tooling/muzzle/references/Source;[Lio/opentelemetry/javaagent/tooling/muzzle/references/Flag;Ljava/lang/String;Lorg/objectweb/asm/Type;Z)Lio/opentelemetry/javaagent/tooling/muzzle/references/ClassRefBuilder;".equals(methodInsnNode.desc)
          && methodInsnNode.getPrevious().getPrevious().getPrevious().getPrevious().getType() == LDC_INSN) {
            LdcInsnNode ldcInsnNode = (LdcInsnNode) methodInsnNode.getPrevious().getPrevious().getPrevious().getPrevious();
            MuzzleReference.Field field = new MuzzleReference.Field();
            field.sources = sources;
            field.flags = flags;
            field.name = (String) ldcInsnNode.cst;
            field.fieldType = getFieldType(types);
            currentReference.fields.add(field);
            sources = new ArrayList<>();
            flags = 0;
          }
          // Look for ClassRefBuilder.addMethod(Source[] methodSources, Flag[] methodFlags, String methodName, Type methodReturnType, Type... methodArgumentTypes)
          else if ("addMethod".equals(methodInsnNode.name)
              && "([Lio/opentelemetry/javaagent/tooling/muzzle/references/Source;[Lio/opentelemetry/javaagent/tooling/muzzle/references/Flag;Ljava/lang/String;Lorg/objectweb/asm/Type;[Lorg/objectweb/asm/Type;)Lio/opentelemetry/javaagent/tooling/muzzle/references/ClassRefBuilder;".equals(methodInsnNode.desc)
          ) {
            String name = getReferenceMethodName(methodInsnNode, types);
            MuzzleReference.Method method = new MuzzleReference.Method();
            method.sources = sources;
            method.flags = flags;
            method.name = name;
            method.methodType = getMethodType(types);
            currentReference.methods.add(method);
            sources = new ArrayList<>();
            flags = 0;
          }
        } else if ("io/opentelemetry/javaagent/tooling/muzzle/references/Source".equals(methodInsnNode.owner)) {
          // Look for new Source(String, int)
          if ("<init>".equals(methodInsnNode.name)
              && "(Ljava/lang/String;I)V".equals(methodInsnNode.desc)
              && methodInsnNode.getPrevious().getType() == LDC_INSN
              && methodInsnNode.getPrevious().getPrevious().getType() == LDC_INSN) {
            String source = ((LdcInsnNode) methodInsnNode.getPrevious().getPrevious()).cst + ":" + ((LdcInsnNode) methodInsnNode.getPrevious()).cst;
            sources.add(source);
          }
        } else if ("org/objectweb/asm/Type".equals(methodInsnNode.owner)) {
          // Look for Type.getType(String)
          if ("getType".equals(methodInsnNode.name)
          && "(Ljava/lang/String;)Lorg/objectweb/asm/Type;".equals(methodInsnNode.desc)
          && methodInsnNode.getPrevious().getType() == LDC_INSN) {
            types.add((String) ((LdcInsnNode) methodInsnNode.getPrevious()).cst);
          }
        }
      } else if (instructionNode.getType() == FIELD_INSN) {
        FieldInsnNode fieldInsnNode = (FieldInsnNode) instructionNode;
        if (fieldInsnNode.getOpcode() == GETSTATIC
          && fieldInsnNode.owner.startsWith("io/opentelemetry/javaagent/tooling/muzzle/references/Flag$")) {
          flags += getFlag(fieldInsnNode);
        }
      }
    }
    if (currentReference != null) {
      this.references.add(currentReference);
    }
  }

  private int getFlag(FieldInsnNode fieldInsnNode) {
    int index = fieldInsnNode.owner.lastIndexOf("$");
    String flagType = index == -1 ? fieldInsnNode.owner :fieldInsnNode.owner.substring(index + 1);
    String flagName = fieldInsnNode.name;
    int flag = MuzzleFlag.convertOtelFlag(flagType, flagName);
    if (flag < 0) {
      throw new IllegalStateException("Failed to extract muzzle reference flag from " + fieldInsnNode.owner + "." + fieldInsnNode.name + " of " + className);
    }
    return flag;
  }

  private MethodNode findMethodNode(ClassNode classNode, String methodName, String methodDesc) {
    return classNode.methods.stream()
        .filter(node -> methodName.equals(node.name) && methodDesc.equals(node.desc))
        .findAny()
        .orElseThrow(() -> new IllegalStateException(
            "Unable to find method " + methodName + " from " + this.className
        ));
  }

  private String getFieldType(List<String> types) {
    if (types.size() != 1) {
      throw new IllegalStateException("Unexpected number of collected types: "+types.size() + " but expected 1");
    }
    String fieldType = types.get(0);
    types.clear();
    return fieldType;
  }

  private String getMethodType(List<String> types) {
    if (types.isEmpty()) {
      throw new IllegalStateException("Unexpected number of collected types: no types but expected at least one");
    }
    String returnType = types.remove(0);
    String methodType = "(" + String.join("", types) + ")" + returnType;
    types.clear();
    return methodType;
  }

  private String getReferenceMethodName(MethodInsnNode methodInsnNode, List<String> types) {
    if (types.isEmpty()) {
      throw new IllegalStateException("Unexpected number of collected types: no types but expected at least one");
    }
    int rewindDistance = 0;
    int parameterCount = types.size() - 1;
    // Rewind the Type.getType() for parameters stored as the vararg parameter
    // Need to rewind 5 instructions per parameter:
    // * dup
    // * ldc (array index)
    // * ldc_w (type descriptor string)
    // * invokestatic (Type.getType() call)
    // * aastore (array store)
    rewindDistance += 5*parameterCount;
    // Rewind vararg array creation
    // Need to rewind 2 instructions:
    // * ldc (array size)
    // * anewarray (array creation)
    rewindDistance += 2;
    // Rewind return type parameter
    // Need to rewind 2 instructions:
    // * ldc_w (type descriptor string)
    // * invokestatic (Type.getType() call)
    rewindDistance+= 2;
    // Finally rewind up to the load instruction with the method name
    rewindDistance++;
    AbstractInsnNode abstractInsnNode = methodInsnNode;
    for (int i = 0; i < rewindDistance; i++) {
      abstractInsnNode = abstractInsnNode.getPrevious();
      if (abstractInsnNode == null) {
        throw new IllegalStateException("Invalid rewind distance: no more instructions");
      }
    }
    if (abstractInsnNode instanceof LdcInsnNode) {
      return (String) ((LdcInsnNode) abstractInsnNode).cst;
    } else {
      throw new IllegalStateException("Unexpected instruction type "+abstractInsnNode.getType() + " when looking for reference method name");
    }
  }

  /**
   * Removes any OpenTelemetry specific muzzle method and interfaces.
   */
  private void cleanUpOTelMuzzle() {
    ClassNode classNode = (ClassNode) this.cv;
    // Remove inheritance from InstrumentationModuleMuzzle
    classNode.interfaces.remove(INSTRUMENTATION_MODULE_MUZZLE_CLASS_NAME);
    // Remove getMuzzleReferences() method
    MethodNode getMuzzleReferencesMethodNode =
        findMethodNode(classNode, GET_MUZZLE_REFERENCES_METHOD_NAME, GET_MUZZLE_REFERENCES_DESC);
    classNode.methods.remove(getMuzzleReferencesMethodNode);
    // Remove registerMuzzleVirtualFields(VirtualFieldMappingsBuilder) method
    MethodNode registerMuzzleVirtualFieldsMethodNode =
        findMethodNode(classNode, REGISTER_MUZZLE_VIRTUAL_FIELDS_METHOD_NAME, REGISTER_MUZZLE_VIRTUAL_FIELDS_DESC);
    classNode.methods.remove(registerMuzzleVirtualFieldsMethodNode);
  }
}
