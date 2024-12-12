package datadog.opentelemetry.tooling;

import datadog.trace.agent.tooling.ExtensionHandler;
import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.agent.tooling.muzzle.ReferenceMatcher;
import java.net.URL;
import java.net.URLConnection;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;

/** Handles OpenTelemetry instrumentations, so they can be loaded into the Datadog tracer. */
public final class OtelExtensionHandler extends ExtensionHandler {

  /** Handler for loading externally built OpenTelemetry extensions. */
  public static final OtelExtensionHandler OPENTELEMETRY = new OtelExtensionHandler();

  private static final String OPENTELEMETRY_MODULE_DESCRIPTOR =
      "META-INF/services/io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule";

  private static final String DATADOG_MODULE_DESCRIPTOR =
      "META-INF/services/datadog.trace.agent.tooling.InstrumenterModule";

  @Override
  public JarEntry mapEntry(JarFile jar, String file) {
    if (DATADOG_MODULE_DESCRIPTOR.equals(file)) {
      // redirect request to include OpenTelemetry instrumentations
      return super.mapEntry(jar, OPENTELEMETRY_MODULE_DESCRIPTOR);
    } else if (file.endsWith("$Muzzle.class")) {
      return new JarEntry(file); // pretend we have a static Muzzle class
    } else {
      return super.mapEntry(jar, file);
    }
  }

  @Override
  public URLConnection mapContent(URL url, JarFile jar, JarEntry entry) {
    String file = entry.getName();
    if (file.endsWith("$Muzzle.class")) {
      return new EmptyMuzzleConnection(url); // generate an empty static Muzzle class
    } else if (file.endsWith(".class")) {
      return new ClassMappingConnection(url, jar, entry, OtelInstrumentationMapper::new);
    } else {
      return new JarFileConnection(url, jar, entry);
    }
  }

  /** Generates an empty static muzzle class for OpenTelemetry instrumentations. */
  static final class EmptyMuzzleConnection extends ClassMappingConnection {

    private static final String REFERENCE_MATCHER_CLASS =
        Type.getInternalName(ReferenceMatcher.class);

    private static final String REFERENCE_CLASS = Type.getInternalName(Reference.class);

    public EmptyMuzzleConnection(URL url) {
      super(url, null, null, null);
    }

    @Override
    protected byte[] doMapBytecode(String unused) {
      String file = url.getFile();
      // remove .class suffix and optional forward-slash prefix
      String className = file.substring(file.charAt(0) == '/' ? 1 : 0, file.length() - 6);
      ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
      cw.visit(
          Opcodes.V1_8,
          Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
          className,
          null,
          "java/lang/Object",
          null);
      MethodVisitor mv =
          cw.visitMethod(
              Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
              "create",
              "()L" + REFERENCE_MATCHER_CLASS + ";",
              null,
              null);
      mv.visitCode();
      mv.visitTypeInsn(Opcodes.NEW, REFERENCE_MATCHER_CLASS);
      mv.visitInsn(Opcodes.DUP);
      mv.visitInsn(Opcodes.ICONST_0);
      mv.visitTypeInsn(Opcodes.ANEWARRAY, REFERENCE_CLASS);
      mv.visitMethodInsn(
          Opcodes.INVOKESPECIAL,
          REFERENCE_MATCHER_CLASS,
          "<init>",
          "([L" + REFERENCE_CLASS + ";)V",
          false);
      mv.visitInsn(Opcodes.ARETURN);
      mv.visitMaxs(0, 0);
      mv.visitEnd();
      cw.visitEnd();
      return cw.toByteArray();
    }
  }
}
