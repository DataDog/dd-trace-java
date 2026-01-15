package datadog.trace.agent.tooling.stratum;

import datadog.trace.agent.tooling.stratum.parser.Parser;
import datadog.trace.api.iast.telemetry.IastMetric;
import datadog.trace.api.iast.telemetry.IastMetricCollector;
import java.util.List;
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
public class StratumManager {

  private static final Logger LOG = LoggerFactory.getLogger(StratumManager.class);

  private final LimitedConcurrentHashMap map;

  public StratumManager(int sourceMappingLimit) {
    // Prevent instantiation
    this.map = new LimitedConcurrentHashMap(sourceMappingLimit);
  }

  public void analyzeClass(final byte[] bytes) {
    if (map.isLimitReached()) {
      return;
    }
    StratumExt s = getDefaultStratum(bytes);
    if (s != null) {
      map.put(s.getName(), s);
    }
  }

  public Stratum get(final String classname) {
    return map.get(classname);
  }

  private SourceMap getResolvedSmap(final String smap) {
    try {
      List<SourceMap> sourceMaps = Parser.parse(smap);

      SourceMap result = Resolver.resolve(sourceMaps.get(0));
      // clean result object to minimize memory usage
      result
          .getStratumList()
          .forEach(stratum -> stratum.getLineInfo().forEach(li -> li.setFileInfo(null)));
      return result;
    } catch (Exception e) {
      LOG.debug("Could not get resolved source map from smap", e);
    }
    return null;
  }

  private StratumExt getDefaultStratum(final byte[] bytes) {
    try {
      String[] classData = extractSourceDebugExtensionASM(bytes);
      if (classData[1] == null) {
        return null;
      }
      SourceMap smap = getResolvedSmap(classData[1]);
      StratumExt stratum = smap != null ? smap.getStratum(smap.getDefaultStratumName()) : null;

      if (stratum == null) {
        return null;
      }

      stratum.setName(classData[0]);
      return stratum;
    } catch (Exception e) {
      LOG.debug("Could not get default stratum from byte array", e);
    }
    return null;
  }

  /** Get name and debug info */
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

  static class LimitedConcurrentHashMap {
    private final int maxSize;
    private volatile boolean limitReached = false;
    private final Map<String, StratumExt> map = new ConcurrentHashMap<>();

    public LimitedConcurrentHashMap(int maxSize) {
      this.maxSize = maxSize;
    }

    public void put(String className, StratumExt value) {
      synchronized (this) {
        if (limitReached) {
          return;
        }
        map.put(className, value);
        if (this.size() >= maxSize) {
          IastMetricCollector.add(IastMetric.SOURCE_MAPPING_LIMIT_REACHED, 1);
          limitReached = true;
        }
      }
    }

    public int size() {
      return map.size();
    }

    public boolean isLimitReached() {
      return limitReached;
    }

    public StratumExt get(String classname) {
      return map.get(classname);
    }
  }
}
