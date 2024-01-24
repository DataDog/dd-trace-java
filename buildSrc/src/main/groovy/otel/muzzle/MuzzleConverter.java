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
  private static final String INSTRUMENTATION_MODULE_MUZZLE_CLASS_NAME = "io/opentelemetry/javaagent/tooling/muzzle/InstrumentationModuleMuzzle";
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

  private final ClassVisitor next;
  private final String className;
  private final List<Reference> references;

  public MuzzleConverter(ClassVisitor classVisitor, String className) {
    super(ASM9, new ClassNode());
    this.next = classVisitor;
    this.className = className;
    this.references = new ArrayList<>();
  }

  @Override
  public void visitEnd() {
    ClassNode cn = (ClassNode) cv;
    if (inheritsInstrumentationModuleMuzzle()) {
      convertHelperClassNames();
      captureMuzzleReferences();
      // TODO Add oll other muzzle methods conversion too
    }

    cn.accept(this.next);
  }

  public boolean inheritsInstrumentationModuleMuzzle() {
    ClassNode classNode = (ClassNode) this.cv;
    return classNode.interfaces.stream().anyMatch(INSTRUMENTATION_MODULE_MUZZLE_CLASS_NAME::equals);
  }

  /**
   * Convert OTel {@code public List getMuzzleHelperClassNames()} method into Datadog {@code public String[] helperClassNames()} method.
   */
  public void convertHelperClassNames() {
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
   * Capture all the helper names from OTel {@code public List getMuzzleHelperClassNames()} method.
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
   * Create the Datadog {@code public String[] helperClassNames()} method instructions.
   * @param helperNames The helper names.
   * @return The Datadog helperClassNames method instruction list.
   */
  private InsnList buildHelperClassNamesInstructions(List<String> helperNames) {
    InsnList list = new InsnList();
    // Create a String array of the right size
    list.add(new IntInsnNode(BIPUSH, helperNames.size()));
    list.add(new TypeInsnNode(ANEWARRAY, STRING_CLASS_NAME));
    // Append each helper name
    for (int index = 0; index < helperNames.size(); index++) {
      list.add(new InsnNode(DUP));
      list.add(new IntInsnNode(BIPUSH, index));
      list.add(new LdcInsnNode(index));
      list.add(new InsnNode(AASTORE));
    }
    // Return the array
    list.add(new InsnNode(ARETURN));
    return list;
  }

  public void captureMuzzleReferences() {
    ClassNode classNode = (ClassNode) this.cv;
    // Look for OTel method
    MethodNode methodNode = findMethodNode(classNode, GET_MUZZLE_REFERENCES_METHOD_NAME, GET_MUZZLE_REFERENCES_DESC);


    Reference currentReference = null;
    for (final AbstractInsnNode instructionNode : methodNode.instructions) {
      if (instructionNode.getType() == METHOD_INSN) {
        MethodInsnNode methodInsnNode = (MethodInsnNode) instructionNode;
        System.out.println("Instruction: " + methodInsnNode.name + " " + methodInsnNode.owner + " " + methodInsnNode.desc);
        // Look for ClassRef.builder(String)
        if ("io/opentelemetry/javaagent/tooling/muzzle/references/ClassRef".equals(methodInsnNode.owner)
            && "builder".equals(methodInsnNode.name)
            && "(Ljava/lang/String;)Lio/opentelemetry/javaagent/tooling/muzzle/references/ClassRefBuilder;".equals(methodInsnNode.desc)
            && methodInsnNode.getPrevious().getType() == LDC_INSN) {
          LdcInsnNode ldcInsnNode = (LdcInsnNode) methodInsnNode.getPrevious();
          if (currentReference != null) {
            this.references.add(currentReference);
          }
          currentReference = new Reference();
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
          } else if ("addSource".equals(methodInsnNode.name)
              && "(Ljava/lang/String;I)Lio/opentelemetry/javaagent/tooling/muzzle/references/ClassRefBuilder;".equals(methodInsnNode.desc)
              && methodInsnNode.getPrevious().getType() == LDC_INSN
              && methodInsnNode.getPrevious().getPrevious().getType() == LDC_INSN) {
            String source = ((LdcInsnNode) methodInsnNode.getPrevious().getPrevious()).cst + ":" + ((LdcInsnNode) methodInsnNode.getPrevious()).cst;
            currentReference.sources.add(source);
          } else if ("addFlag".equals(methodInsnNode.name)
              && "(Lio/opentelemetry/javaagent/tooling/muzzle/references/Flag;)Lio/opentelemetry/javaagent/tooling/muzzle/references/ClassRefBuilder;".equals(methodInsnNode.desc)
              && methodInsnNode.getPrevious().getType() == FIELD_INSN
              && methodInsnNode.getPrevious().getOpcode() == GETSTATIC) {
            currentReference.flags += getFlag(methodInsnNode);
          } else if ("addInterfaceName".equals(methodInsnNode.name)
              && "(Ljava/lang/String;)Lio/opentelemetry/javaagent/tooling/muzzle/references/ClassRefBuilder;".equals(methodInsnNode.desc)
              && methodInsnNode.getPrevious().getType() == LDC_INSN) {
            currentReference.interfaces.add((String) ((LdcInsnNode) methodInsnNode.getPrevious()).cst);
          }
        }
      }
    }

    // TODO Debug
    for (Reference reference : this.references) {
      System.out.println("Found reference: " + reference);
    }
  }

  private int getFlag(MethodInsnNode methodInsnNode) {
    FieldInsnNode fieldInsnNode = (FieldInsnNode) methodInsnNode.getPrevious();
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

  public static class Reference {
    public List<String> sources = new ArrayList<>();
    public int flags = 0;
    public String className;
    public String superName;
    public List<String> interfaces = new ArrayList<>();

    @Override
    public String toString() {
      return "Reference{" +
          "sources=" + sources +
          ", flags=" + flags +
          ", className='" + className + '\'' +
          ", superName='" + superName + '\'' +
          ", interfaces=" + interfaces +
          '}';
    }
  }
}
