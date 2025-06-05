package datadog.trace.agent.tooling;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;

import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.commons.ClassRemapper;
import net.bytebuddy.jar.asm.commons.Remapper;

/** Shades advice bytecode by applying relocations to all references. */
public final class AdviceShader {
  private final Map<String, String> relocations;
  private final List<String> helperNames;

  private volatile Remapper remapper;

  /**
   * Used when installing {@link InstrumenterModule}s. Ensures any injected helpers have unique
   * names so the original and relocated modules can inject helpers into the same class-loader.
   */
  public static AdviceShader with(InstrumenterModule module) {
    if (module.adviceShading() != null) {
      return new AdviceShader(module.adviceShading(), asList(module.helperClassNames()));
    }
    return null;
  }

  /** Used to generate and check muzzle references. Only applies relocations declared in modules. */
  public static AdviceShader with(Map<String, String> relocations) {
    if (relocations != null) {
      return new AdviceShader(relocations, emptyList());
    }
    return null;
  }

  private AdviceShader(Map<String, String> relocations, List<String> helperNames) {
    this.relocations = relocations;
    this.helperNames = helperNames;
  }

  /** Applies shading before calling the given {@link ClassVisitor}. */
  public ClassVisitor shadeClass(ClassVisitor cv) {
    if (null == remapper) {
      remapper = new AdviceMapper();
    }
    return new ClassRemapper(cv, remapper);
  }

  /** Returns the result of shading the given bytecode. */
  public byte[] shadeClass(byte[] bytecode) {
    ClassReader cr = new ClassReader(bytecode);
    ClassWriter cw = new ClassWriter(null, 0);
    cr.accept(shadeClass(cw), 0);
    return cw.toByteArray();
  }

  /** Generates a unique shaded name for the given helper. */
  public String uniqueHelper(String dottedName) {
    int packageEnd = dottedName.lastIndexOf('.');
    if (packageEnd > 0) {
      return dottedName.substring(0, packageEnd + 1)
          + "shaded"
          + relocations.hashCode()
          + dottedName.substring(packageEnd);
    }
    return dottedName;
  }

  final class AdviceMapper extends Remapper {
    private final DDCache<String, String> mappingCache = DDCaches.newFixedSizeCache(64);

    /** Flattened sequence of old-prefix, new-prefix relocations. */
    private final String[] prefixes;

    private final Map<String, String> helperMapping;

    AdviceMapper() {
      // record the unique names that we've given to injected helpers
      this.helperMapping = new HashMap<>(helperNames.size() + 1, 1f);
      for (String h : helperNames) {
        this.helperMapping.put(h.replace('.', '/'), uniqueHelper(h).replace('.', '/'));
      }
      // convert relocations to a flattened sequence: old-prefix, new-prefix, etc.
      this.prefixes = new String[relocations.size() * 2];
      int i = 0;
      for (Map.Entry<String, String> e : relocations.entrySet()) {
        String oldPrefix = e.getKey();
        String newPrefix = e.getValue();
        if (oldPrefix.indexOf('.') > 0) {
          // accept dotted prefixes, but store them in their internal form
          this.prefixes[i++] = oldPrefix.replace('.', '/');
          this.prefixes[i++] = newPrefix.replace('.', '/');
        } else {
          this.prefixes[i++] = oldPrefix;
          this.prefixes[i++] = newPrefix;
        }
      }
    }

    @Override
    public String map(String internalName) {
      String uniqueName = helperMapping.get(internalName);
      if (uniqueName != null) {
        return uniqueName;
      }
      if (internalName.startsWith("java/")
          || internalName.startsWith("datadog/")
          || internalName.startsWith("net/bytebuddy/")) {
        return internalName; // never shade these references
      }
      return mappingCache.computeIfAbsent(internalName, this::shadeInternalName);
    }

    @Override
    public Object mapValue(Object value) {
      if (value instanceof String) {
        String text = (String) value;
        if (text.isEmpty()) {
          return text;
        } else if (text.indexOf('.') > 0) {
          return shadeDottedName(text);
        } else {
          return shadeInternalName(text);
        }
      } else {
        return super.mapValue(value);
      }
    }

    private String shadeInternalName(String internalName) {
      for (int i = 0; i < prefixes.length; i += 2) {
        if (internalName.startsWith(prefixes[i])) {
          return prefixes[i + 1] + internalName.substring(prefixes[i].length());
        }
      }
      return internalName;
    }

    private String shadeDottedName(String name) {
      String internalName = name.replace('.', '/');
      for (int i = 0; i < prefixes.length; i += 2) {
        if (internalName.startsWith(prefixes[i])) {
          return prefixes[i + 1].replace('/', '.') + name.substring(prefixes[i].length());
        }
      }
      return name;
    }
  }
}
