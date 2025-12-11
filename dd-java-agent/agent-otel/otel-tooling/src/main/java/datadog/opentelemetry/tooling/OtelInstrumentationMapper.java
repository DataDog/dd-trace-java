package datadog.opentelemetry.tooling;

import static datadog.trace.agent.tooling.ExtensionHandler.MAP_LOGGING;

import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.ContextStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
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
      Collections.singleton(
          "io/opentelemetry/javaagent/tooling/muzzle/InstrumentationModuleMuzzle");

  public OtelInstrumentationMapper(ClassVisitor classVisitor) {
    super(classVisitor, Renamer.INSTANCE);
  }

  @Override
  protected MethodVisitor createMethodRemapper(MethodVisitor methodVisitor) {
    return new OtelMethodCallMapper(methodVisitor, remapper);
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
          "io/opentelemetry/javaagent/shaded/instrumentation/api/util/VirtualField",
          Type.getInternalName(ContextStore.class));
      RENAMED_TYPES.put(
          "io/opentelemetry/javaagent/tooling/muzzle/references/ClassRefBuilder",
          Type.getInternalName(OtelMuzzleRefBuilder.class));
      RENAMED_TYPES.put(
          "io/opentelemetry/javaagent/tooling/muzzle/references/ClassRef",
          Type.getInternalName(OtelMuzzleRefBuilder.ClassRef.class));
      RENAMED_TYPES.put(
          "io/opentelemetry/javaagent/tooling/muzzle/references/Flag",
          Type.getInternalName(OtelMuzzleRefBuilder.Flag.class));
      RENAMED_TYPES.put(
          "io/opentelemetry/javaagent/tooling/muzzle/references/Flag$VisibilityFlag",
          Type.getInternalName(OtelMuzzleRefBuilder.Flag.class));
      RENAMED_TYPES.put(
          "io/opentelemetry/javaagent/tooling/muzzle/references/Flag$MinimumVisibilityFlag",
          Type.getInternalName(OtelMuzzleRefBuilder.Flag.class));
      RENAMED_TYPES.put(
          "io/opentelemetry/javaagent/tooling/muzzle/references/Flag$ManifestationFlag",
          Type.getInternalName(OtelMuzzleRefBuilder.Flag.class));
      RENAMED_TYPES.put(
          "io/opentelemetry/javaagent/tooling/muzzle/references/Flag$OwnershipFlag",
          Type.getInternalName(OtelMuzzleRefBuilder.Flag.class));
      RENAMED_TYPES.put(
          "io/opentelemetry/javaagent/tooling/muzzle/references/Source",
          Type.getInternalName(OtelMuzzleRefBuilder.Source.class));
      RENAMED_TYPES.put(
          "io/opentelemetry/instrumentation/CallDepth",
          "datadog/trace/bootstrap/otel/instrumentation/CallDepth");
      RENAMED_TYPES.put(
          "io/opentelemetry/instrumentation/Java8BytecodeBridge",
          "datadog/trace/bootstrap/otel/instrumentation/Java8BytecodeBridge");
    }

    /** OpenTelemetry and related packages shaded inside the tracer. */
    private static final Map<String, String> RENAMED_PACKAGES = new HashMap<>();

    static {
      RENAMED_PACKAGES.put(
          "io/opentelemetry/javaagent/shaded/io/opentelemetry/", "datadog/trace/bootstrap/otel/");

      RENAMED_PACKAGES.put("io/opentelemetry/api/", "datadog/trace/bootstrap/otel/api/");
      RENAMED_PACKAGES.put("io/opentelemetry/context/", "datadog/trace/bootstrap/otel/context/");
      RENAMED_PACKAGES.put("io/opentelemetry/semconv/", "datadog/trace/bootstrap/otel/semconv/");

      RENAMED_PACKAGES.put(
          "io/opentelemetry/instrumentation/api/",
          "datadog/trace/bootstrap/otel/instrumentation/api/");
      RENAMED_PACKAGES.put(
          "io/opentelemetry/instrumentation/http/",
          "datadog/trace/bootstrap/otel/instrumentation/http/");
      RENAMED_PACKAGES.put(
          "io/opentelemetry/instrumentation/servlet/",
          "datadog/trace/bootstrap/otel/instrumentation/servlet/");
      RENAMED_PACKAGES.put(
          "io/opentelemetry/instrumentation/internal/",
          "datadog/trace/bootstrap/otel/instrumentation/internal/");

      RENAMED_PACKAGES.put(
          "io/opentelemetry/javaagent/bootstrap/", "datadog/trace/bootstrap/otel/instrumentation/");

      // we want to keep this package unchanged so it matches against any unshaded extensions
      // dropped in at runtime; use replace to stop it being transformed by the shadow plugin
      RENAMED_PACKAGES.put("org|objectweb|asm|".replace('|', '/'), "net/bytebuddy/jar/asm/");
    }

    @Override
    public String map(String internalName) {
      String rename = RENAMED_TYPES.get(internalName);
      if (null != rename) {
        return rename;
      }
      for (Map.Entry<String, String> mapping : RENAMED_PACKAGES.entrySet()) {
        if (internalName.startsWith(mapping.getKey())) {
          return mapping.getValue() + internalName.substring(mapping.getKey().length());
        }
      }
      return MAP_LOGGING.apply(internalName);
    }
  }
}
