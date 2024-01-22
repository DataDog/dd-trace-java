package otel

import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
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

/** This visitor use the ASM Tree API to converts muzzle methods */
class MuzzleConverter extends ClassVisitor {
  private final ClassVisitor next
  private final String className

  MuzzleConverter(ClassVisitor classVisitor, String className) {
    super(ASM9, new ClassNode())
    this.next = classVisitor
    this.className = className
  }

  @Override
  void visitEnd() {
    ClassNode cn = (ClassNode) cv
    if (inheritsInstrumentationModuleMuzzle()) {
      convertHelpers()
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
      it.type == AbstractInsnNode.METHOD_INSN
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
}
