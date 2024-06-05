package datadog.opentelemetry.tooling;

import static datadog.trace.agent.tooling.ExtensionHandler.MAP_LOGGING;

import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Type;
import net.bytebuddy.jar.asm.commons.ClassRemapper;
import net.bytebuddy.jar.asm.commons.Remapper;

/** Maps OpenTelemetry instrumentations to use the Datadog {@link InstrumenterModule} API. */
public final class OtelInstrumentationMapper extends ClassRemapper {

  private static final Set<String> UNSUPPORTED_TYPES =
      new HashSet<>(
          Arrays.asList("io/opentelemetry/javaagent/tooling/muzzle/InstrumentationModuleMuzzle"));

  private static final Set<String> UNSUPPORTED_METHODS =
      new HashSet<>(Arrays.asList("getMuzzleReferences"));

  public OtelInstrumentationMapper(ClassVisitor classVisitor) {
    super(classVisitor, Renamer.INSTANCE);
  }

  @Override
  public void visit(
      int version,
      int access,
      String name,
      String signature,
      String superName,
      String[] interfaces) {
    super.visit(version, access, name, signature, superName, removeUnsupportedTypes(interfaces));
  }

  @Override
  public MethodVisitor visitMethod(
      int access, String name, String descriptor, String signature, String[] exceptions) {
    if (UNSUPPORTED_METHODS.contains(name)) {
      return null; // remove unsupported method
    }
    return super.visitMethod(access, name, descriptor, signature, exceptions);
  }

  private String[] removeUnsupportedTypes(String[] interfaces) {
    List<String> filtered = null;
    for (int i = interfaces.length - 1; i >= 0; i--) {
      if (UNSUPPORTED_TYPES.contains(interfaces[i])) {
        if (null == filtered) {
          filtered = new ArrayList<>(Arrays.asList(interfaces));
        }
        filtered.remove(i); // remove unsupported interface
      }
    }
    return null != filtered ? filtered.toArray(new String[0]) : interfaces;
  }

  static final class Renamer extends Remapper {
    static final Renamer INSTANCE = new Renamer();

    private static final String OTEL_JAVAAGENT_SHADED_PREFIX =
        "io/opentelemetry/javaagent/shaded/io/opentelemetry/";

    /** Datadog equivalent of OpenTelemetry instrumentation classes. */
    private static final Map<String, String> RENAMED_TYPES = new HashMap<>();

    static {
      RENAMED_TYPES.put(
          "io/opentelemetry/javaagent/extension/instrumentation/InstrumentationModule",
          Type.getInternalName(OtelInstrumenterModule.class));
      RENAMED_TYPES.put(
          "io/opentelemetry/javaagent/extension/instrumentation/TypeInstrumentation",
          Type.getInternalName(OtelInstrumenter.class));
      RENAMED_TYPES.put(
          "io/opentelemetry/javaagent/extension/instrumentation/TypeTransformer",
          Type.getInternalName(OtelTransformer.class));
      RENAMED_TYPES.put(
          "io/opentelemetry/javaagent/extension/matcher/AgentElementMatchers",
          Type.getInternalName(OtelElementMatchers.class));
      RENAMED_TYPES.put(
          "io/opentelemetry/javaagent/tooling/muzzle/VirtualFieldMappingsBuilder",
          Type.getInternalName(OtelInstrumenterModule.VirtualFieldBuilder.class));
      RENAMED_TYPES.put(
          "io/opentelemetry/javaagent/bootstrap/Java8BytecodeBridge",
          "datadog/trace/bootstrap/otel/Java8BytecodeBridge");
    }

    @Override
    public String map(String internalName) {
      String rename = RENAMED_TYPES.get(internalName);
      if (null != rename) {
        return rename;
      }
      // map OpenTelemetry's shaded API to our embedded copy
      if (internalName.startsWith(OTEL_JAVAAGENT_SHADED_PREFIX)) {
        return "datadog/trace/bootstrap/otel/"
            + internalName.substring(OTEL_JAVAAGENT_SHADED_PREFIX.length());
      }
      return MAP_LOGGING.apply(internalName);
    }
  }
}
