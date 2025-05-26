import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.utils.TraceUtils
import org.apache.commons.io.IOUtils
import org.springframework.asm.ClassReader
import org.springframework.asm.ClassVisitor
import org.springframework.asm.ClassWriter
import org.springframework.asm.MethodVisitor
import org.springframework.asm.Opcodes
import org.springframework.boot.devtools.restart.classloader.ClassLoaderFile
import org.springframework.boot.devtools.restart.classloader.ClassLoaderFiles
import org.springframework.boot.devtools.restart.classloader.RestartClassLoader

class RestartClassLoaderTest extends AgentTestRunner {
  def 'should instrument reloaded classes'() {
    given:
    def repository = new ClassLoaderFiles()
    def parent = new URLClassLoader(new URL[]{
      GroovyObject.class.getProtectionDomain().getCodeSource().getLocation()
    }, (ClassLoader)null)
    def cl = new RestartClassLoader(parent,
    new URL[] {
      TestBean.class.getProtectionDomain().getCodeSource().getLocation()
    },
    repository)
    when:
    TestBean.test()
    then:
    assertTraces(0, {})
    when:
    def content = IOUtils.toByteArray(new URL(TracingBean.class.getProtectionDomain().getCodeSource().getLocation().toString() + "TracingBean.class"))
    // We need to inject a different bytecode (the one of TracingBean) by emulating it was in TestBean like if TestBean has been recompiled on the fly and swapped.
    // One option is to kind of cheat is to quickly manipulate the TracingBean bytecode to have a type name change
    ClassReader reader = new ClassReader(content)
    ClassWriter writer = new ClassWriter(0)
    ClassVisitor transformer = new ClassVisitor(Opcodes.ASM5, writer) {
      @Override
      void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
        super.visit(version, access, "TestBean", signature, superName, interfaces)
      }
    }
    reader.accept( transformer, 0)
    repository.addFile("TestBean.class", new ClassLoaderFile(ClassLoaderFile.Kind.MODIFIED, writer.toByteArray()))
    cl.loadClass(TestBean.class.getName()).getMethod("test").invoke(null)
    then:
    assertTraces(1, {
      trace(1) {
        TraceUtils.basicSpan(it, "trace.annotation","TestBean.test",null, null, ["component":"trace"] )
      }
    })
  }
}
