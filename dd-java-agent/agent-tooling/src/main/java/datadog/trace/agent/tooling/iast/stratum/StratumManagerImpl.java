package datadog.trace.agent.tooling.iast.stratum;

import datadog.trace.agent.tooling.iast.stratum.parser.Parser;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.bytebuddy.utility.OpenedClassReader;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages SMAP information for classes <a
 * href="https://jakarta.ee/specifications/debugging/2.0/jdsol-spec-2.0#stratumsection">...</a>
 */
public class StratumManagerImpl {

  private static final Logger LOG = LoggerFactory.getLogger(StratumManagerImpl.class);

  private StratumManagerImpl() {
    // Prevent instantiation
  }

  private final Map<String, StratumExt> map = new ConcurrentHashMap<>();

  public final StratumExt NO_DEBUG_INFO = new StratumExt();

  private boolean EMPTY_DEBUG_INFO;

  public static final StratumManagerImpl INSTANCE = new StratumManagerImpl();

  public static boolean shouldBeAnalyzed(final String internalClassName) {
    return internalClassName.contains("jsp")
        && (internalClassName.contains("_jsp")
            || internalClassName.contains("jsp_")
            || internalClassName.contains("_tag"));
  }

  public void analyzeClass(final byte[] bytes) {
    StratumExt s = getDefaultStratum(bytes);
    if (s != null) {
      map.put(s.getName(), s);
    }
  }

  public Stratum get(final String classname) {
    StratumExt s = map.get(classname);
    if (s != null) {
      return s;
    } else if (EMPTY_DEBUG_INFO) {
      return NO_DEBUG_INFO;
    } else {
      return null;
    }
  }

  private SourceMap getResolvedSmap(final String smap) {
    try {
      SourceMap[] sourceMaps = new Parser().parse(smap);

      SourceMap result = new Resolver().resolve(sourceMaps[0]);
      // clean result object to minimize memory usage
      result
          .getStratumList()
          .forEach(stratum -> stratum.getLineInfo().forEach(li -> li.setFileInfo(null)));
      return result;
    } catch (Exception e) {
      LOG.error("Could not get resolved source map from smap", e);
    }
    return null;
  }

  private StratumExt getDefaultStratum(final byte[] bytes) {
    try {
      String[] classData = extractSourceDebugExtensionASM(bytes);
      if (classData[1] == null) {
        EMPTY_DEBUG_INFO = true;
        return null;
      }
      SourceMap smap = getResolvedSmap(classData[1]);
      StratumExt stratum = smap != null ? smap.getStratum(smap.getDefaultStratumName()) : null;

      if (stratum == null) {
        EMPTY_DEBUG_INFO = true;
        return null;
      }

      stratum.setName(classData[0]);
      return stratum;
    } catch (Exception e) {
      LOG.error("Could not get default stratum from byte array", e);
    }
    return null;
  }

  private String[] extractSourceDebugExtensionASM(final byte[] classBytes) {
    ClassReader cr = new ClassReader(classBytes);
    final String[] result = new String[2];
    cr.accept(
        new ClassVisitor(OpenedClassReader.ASM_API) {
          @Override
          public void visit(
              final int version,
              final int access,
              final String name,
              final String signature,
              final String superName,
              final String[] interfaces) {
            result[0] = name.replace('/', '.');
          }

          @Override
          public void visitSource(final String source, final String debug) {
            result[1] = debug;
          }
        },
        ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);

    return result;
  }
}
