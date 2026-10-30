package com.example.jsonbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.management.ClassLoadingMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AdminController {

  private final ObjectMapper mapper;
  private final boolean newMapperBlackbird = hasBlackbird(new ObjectMapper());

  public AdminController(ObjectMapper mapper) {
    this.mapper = mapper;
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
    ClassLoadingMXBean cl = ManagementFactory.getClassLoadingMXBean();
    line(sb, "cpuNanos", cpu);
    line(sb, "gcCount", gcCount);
    line(sb, "gcMs", gcMs);
    line(sb, "loadedClasses", cl.getLoadedClassCount());
    line(sb, "unloadedClasses", cl.getUnloadedClassCount());
    line(sb, "springMapperBlackbird", hasBlackbird(mapper) ? 1 : 0);
    line(sb, "newMapperBlackbird", newMapperBlackbird ? 1 : 0);
    return sb.toString();
  }

  private static boolean hasBlackbird(ObjectMapper m) {
    for (Object id : m.getRegisteredModuleIds()) {
      if (String.valueOf(id).endsWith(".BlackbirdModule")) {
        return true;
      }
    }
    return false;
  }

  private static void line(StringBuilder sb, String key, long value) {
    sb.append(key).append('=').append(value).append('\n');
  }
}
