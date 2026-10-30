package com.example.stmtcachebench;

import com.mysql.cj.conf.PropertyKey;
import com.mysql.cj.jdbc.JdbcConnection;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AdminController {

  private final DataSource dataSource;

  public AdminController(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  @GetMapping("/admin/stats")
  public String stats() {
    StringBuilder sb = new StringBuilder(256);
    long cpu = -1;
    java.lang.management.OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
    if (os instanceof com.sun.management.OperatingSystemMXBean) {
      cpu = ((com.sun.management.OperatingSystemMXBean) os).getProcessCpuTime();
    }
    long gcCount = 0;
    long gcMs = 0;
    for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
      gcCount += Math.max(0, gc.getCollectionCount());
      gcMs += Math.max(0, gc.getCollectionTime());
    }
    long cache = -1;
    long cached = -1;
    try (Connection c = dataSource.getConnection()) {
      JdbcConnection jc = c.unwrap(JdbcConnection.class);
      cache = jc.getPropertySet().getBooleanProperty(PropertyKey.cachePrepStmts).getValue() ? 1 : 0;
      cached = cachedStatements(jc);
    } catch (Exception ignored) {
    }
    line(sb, "cpuNanos", cpu);
    line(sb, "gcCount", gcCount);
    line(sb, "gcMs", gcMs);
    line(sb, "cachePrepStmts", cache);
    line(sb, "cachedStatements", cached);
    return sb.toString();
  }

  private static long cachedStatements(Object impl) {
    for (Class<?> k = impl.getClass(); k != null; k = k.getSuperclass()) {
      try {
        Field f = k.getDeclaredField("queryInfoCache");
        f.setAccessible(true);
        Object cache = f.get(impl);
        if (cache == null) {
          return -1;
        }
        Field inner = cache.getClass().getDeclaredField("cache");
        inner.setAccessible(true);
        return ((Map<?, ?>) inner.get(cache)).size();
      } catch (NoSuchFieldException e) {
        continue;
      } catch (ReflectiveOperationException | RuntimeException e) {
        return -1;
      }
    }
    return -1;
  }

  private static void line(StringBuilder sb, String key, long value) {
    sb.append(key).append('=').append(value).append('\n');
  }
}
