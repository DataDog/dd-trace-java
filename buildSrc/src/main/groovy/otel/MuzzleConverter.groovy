package otel

import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.IntInsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.TypeInsnNode

import static org.objectweb.asm.Opcodes.AASTORE
import static org.objectweb.asm.Opcodes.ANEWARRAY
import static org.objectweb.asm.Opcodes.ARETURN
import static org.objectweb.asm.Opcodes.ASM9
import static org.objectweb.asm.Opcodes.BIPUSH
import static org.objectweb.asm.Opcodes.DUP
import static org.objectweb.asm.Opcodes.GETSTATIC
import static org.objectweb.asm.tree.AbstractInsnNode.FIELD_INSN
import static org.objectweb.asm.tree.AbstractInsnNode.LDC_INSN
import static org.objectweb.asm.tree.AbstractInsnNode.METHOD_INSN

/** This visitor use the ASM Tree API to converts muzzle methods */
class MuzzleConverter extends ClassVisitor {
  private final ClassVisitor next
  private final String className
  private List<Reference> references

  MuzzleConverter(ClassVisitor classVisitor, String className) {
    super(ASM9, new ClassNode())
    this.next = classVisitor
    this.className = className
    this.references = []
  }

  @Override
  void visitEnd() {
    ClassNode cn = (ClassNode) cv
    if (inheritsInstrumentationModuleMuzzle()) {
      convertHelpers()
      captureReferences()
      // TODO Add oll other muzzle methods conversion too
    }
    cn.accept(this.next)
  }

  boolean inheritsInstrumentationModuleMuzzle() {
    ClassNode classNode = (ClassNode) this.cv
    return classNode.interfaces.find {
      'io/opentelemetry/javaagent/tooling/muzzle/InstrumentationModuleMuzzle' == it
    } != null
  }

  /** Convert OTel {@code public List getMuzzleHelperClassNames()} into DD {@code public String[] helperClassNames()}. */
  void convertHelpers() {
    ClassNode classNode = (ClassNode) this.cv
    // Look for OTel method
    def methodNode = classNode.methods.find {
      it.name == 'getMuzzleHelperClassNames' && it.desc == '()Ljava/util/List;'
    }
    // Update signature to DD one
    methodNode.name = 'helperClassNames'
    methodNode.desc = '()[Ljava/lang/String;'
    // Find all the helper names
    def helperNames = methodNode.instructions.findAll {
      it.type == METHOD_INSN
    }.collect {
      def node = (MethodInsnNode) it
      if ('add' == node.name && node.desc == '(Ljava/lang/Object;)Z' && node.previous instanceof LdcInsnNode) {
        return ((LdcInsnNode)node.previous).cst
      } else {
        return null
      }
    }.findAll {
      it != null
    }
    /*
     * Update method implementation
     */
    def list = new InsnList()
    // Create a String array of the right size
    list.add(new IntInsnNode(BIPUSH, helperNames.size()))
    list.add(new TypeInsnNode(ANEWARRAY, "java/lang/String"))
    // Append each helper name
    helperNames.eachWithIndex {it,index ->
      list.add(new InsnNode(DUP))
      list.add(new IntInsnNode(BIPUSH, index))
      list.add(new LdcInsnNode(it))
      list.add(new InsnNode(AASTORE))
    }
    // Return the array
    list.add(new InsnNode(ARETURN))
    methodNode.instructions = list
  }

  void captureReferences() {
    ClassNode classNode = (ClassNode) this.cv
    // Look for OTel method
    def methodNode = classNode.methods.find {
      it.name == 'getMuzzleReferences' && it.desc == '()Ljava/util/Map;'
    }

    Reference currentReference = null
    for (final def instructionNode in methodNode.instructions) {
      if (instructionNode.type == METHOD_INSN) {
        MethodInsnNode methodInsnNode = (MethodInsnNode) instructionNode
        println "Instruction: $methodInsnNode.name $methodInsnNode.owner $methodInsnNode.desc"
        // Look for ClassRef.builder(String)
        if ('io/opentelemetry/javaagent/tooling/muzzle/references/ClassRef' == methodInsnNode.owner
          && 'builder' == methodInsnNode.name
          && '(Ljava/lang/String;)Lio/opentelemetry/javaagent/tooling/muzzle/references/ClassRefBuilder;' == methodInsnNode.desc
          && methodInsnNode.previous.type == LDC_INSN) {
          LdcInsnNode ldcInsnNode = (LdcInsnNode) methodInsnNode.previous
          if (currentReference != null) {
            this.references += currentReference
          }
          currentReference = new Reference()
          currentReference.className = ldcInsnNode.cst
        }
        else if ('io/opentelemetry/javaagent/tooling/muzzle/references/ClassRefBuilder' == methodInsnNode.owner) {
          if (currentReference == null) {
            throw new IllegalStateException("Failed to capture muzzle references of $className: no current reference")
          }
          // Look for ClassRefBuilder.setSuperClassName(String)
          if ('setSuperClassName' == methodInsnNode.name
            && '(Ljava/lang/String;)Lio/opentelemetry/javaagent/tooling/muzzle/references/ClassRefBuilder;' == methodInsnNode.desc
            && methodInsnNode.previous.type == LDC_INSN) {
            currentReference.superName = (String) ((LdcInsnNode) methodInsnNode.previous).cst
          }
          // Look for ClassRefBuilder.addSource(String, int)
          // NOTE: Call for ClassRefBuilder.addSource(String) will be forwarded to ClassRefBuilder.addSource(String, int)
          else if ('addSource' == methodInsnNode.name
            && '(Ljava/lang/String;I)Lio/opentelemetry/javaagent/tooling/muzzle/references/ClassRefBuilder;' == methodInsnNode.desc
            && methodInsnNode.previous.type == LDC_INSN
            && methodInsnNode.previous.previous.type == LDC_INSN) {
            String source = (String) ((LdcInsnNode) methodInsnNode.previous.previous).cst + ':' + ((LdcInsnNode) methodInsnNode.previous).cst
            currentReference.sources += source
          }
          // Look for ClassRefBuilder.addFlag(Flag)
          else if ('addFlag' == methodInsnNode.name
            && '(Lio/opentelemetry/javaagent/tooling/muzzle/references/Flag;)Lio/opentelemetry/javaagent/tooling/muzzle/references/ClassRefBuilder;' == methodInsnNode.desc
            && methodInsnNode.previous.type == FIELD_INSN
            && methodInsnNode.previous.opcode == GETSTATIC) {
            FieldInsnNode fieldInsnNode = (FieldInsnNode) methodInsnNode.previous
            int flag = MuzzleFlag.extractFlag(fieldInsnNode)
            if (flag < 0) {
              throw new IllegalStateException("Failed to extract muzzle reference flag from ${fieldInsnNode.owner}.${fieldInsnNode.name} of $className")
            }
            currentReference.flags += flag
          }
          // Look for ClassRefBuilder.addInterfaceName(String)
          // TODO ClassRefBuilder.addInterfaceName(Collection<String>) is not supported -- It does not seem to be used
          else if ('addInterfaceName' == methodInsnNode.name
            && '(Ljava/lang/String;)Lio/opentelemetry/javaagent/tooling/muzzle/references/ClassRefBuilder;' == methodInsnNode.desc
            && methodInsnNode.previous.type == LDC_INSN) {
            currentReference.interfaces += (String) ((LdcInsnNode) methodInsnNode.previous).cst
          }
        }
      }

    }

    // TODO Debug
    references.each {
      println "Found reference: $it"
    }
  }

  class Reference {
    String[] sources = []
    int flags = 0
    String className
    String superName
    String[] interfaces = []
//    Field[] fields;
//    Method[] methods;

    @Override
    String toString() {
      return "Reference{" +
        "sources=" + Arrays.toString(sources) +
        ", flags=" + flags +
        ", className='" + className + '\'' +
        ", superName='" + superName + '\'' +
        ", interfaces=" + Arrays.toString(interfaces) +
        '}'
    }
  }
}
