package com.datadog.profiling.controller.openjdk.events;

import datadog.environment.JavaVirtualMachine;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class SmapEntryCache {
  static class AnnotatedRegion {
    final long startAddress;
    final String description;

    AnnotatedRegion(long startAddress, String description) {
      this.startAddress = startAddress;
      this.description = description;
    }
  }

  private static final Logger log = LoggerFactory.getLogger(SmapEntryCache.class);

  private static final long VSYSCALL_START_ADDRESS = 0xffffffffff600000L;
  private static final String VSYSCALL_START_ADDRESS_STR = Long.toHexString(VSYSCALL_START_ADDRESS);
  private static final Path SMAPS_PATH = Paths.get("/proc/self/smaps");

  private final Object[] events = new Object[] {new ArrayList<>(), new ArrayList<>()};

  private volatile long lastTimestamp = 0L;
  private volatile int index = 0;
  private static final AtomicLongFieldUpdater<SmapEntryCache> UPDATER =
      AtomicLongFieldUpdater.newUpdater(SmapEntryCache.class, "lastTimestamp");
  private static final AtomicIntegerFieldUpdater<SmapEntryCache> INDEX_UPDATER =
      AtomicIntegerFieldUpdater.newUpdater(SmapEntryCache.class, "index");

  private final long ttl;
  private final Path smapsPath;

  SmapEntryCache(Duration ttl) {
    this(ttl, SMAPS_PATH);
  }

  SmapEntryCache(Duration ttl, Path smapsPath) {
    this.ttl = ttl.toNanos();
    this.smapsPath = smapsPath;
  }

  // @VisibleForTesting
  void invalidate() {
    UPDATER.getAndSet(this, System.nanoTime() - (2 * ttl));
  }

  @SuppressWarnings("unchecked")
  public List<SmapEntryEvent> getEvents() {
    long prevTimestamp = lastTimestamp;
    long thisTimestamp = System.nanoTime();
    if (thisTimestamp - prevTimestamp > ttl) {
      if (UPDATER.compareAndSet(this, prevTimestamp, thisTimestamp)) {
        int currentIdx = INDEX_UPDATER.updateAndGet(this, x -> (x + 1) % 2);
        List<SmapEntryEvent> eventList = (List<SmapEntryEvent>) events[currentIdx];
        eventList.clear();
        collectEvents(eventList);
        return eventList;
      }
    }
    return (List<SmapEntryEvent>) events[index];
  }

  // accessible for testing
  static AnnotatedRegion fromAnnotatedEntry(String line, int javaVersion) {
    boolean isRegion = line.startsWith("0x");
    if (isRegion) {
      int descIndex = javaVersion == 23 ? 5 : 8;
      // Java 23
      // 0x0000000420000000 - 0x000000043b000000   452984832  rw-p 00000000    JAVAHEAP
      // ---
      // Java 24-25
      // 0x0000000448800000-0x000000049d800000   1426063360 rw-p   1425514496 0 4K com  JAVAHEAP

      // unify the format of address range for Java 23 and 24+
      line = line.replace(" - ", "-");
      boolean isVsyscall =
          line.startsWith("0x" + VSYSCALL_START_ADDRESS_STR); // can't be parsed to Long safely(?)
      long startAddress = -1;
      int dashIndex = line.indexOf('-');
      if (dashIndex > 0) {
        startAddress = isVsyscall ? -0x1000 - 1 : Long.decode(line.substring(0, dashIndex));
        String description = extractElement(line, descIndex, dashIndex + 1);
        if (description == null || description.isEmpty() || "-".equals(description)) {
          return new AnnotatedRegion(startAddress, "UNDEFINED");
        } else if (description.startsWith("STACK")) {
          return new AnnotatedRegion(startAddress, "STACK");
        } else if (description.startsWith("[") || description.startsWith("/")) {
          return new AnnotatedRegion(startAddress, "SYSTEM");
        } else {
          return new AnnotatedRegion(startAddress, description);
        }
      }
    }
    return null;
  }

  static String extractElement(String line, int index, int from) {
    int wsCount = 0;
    int wsIndex = line.indexOf(' ', from + 1);
    int fromIndex = from;
    int limit = line.length() - 1;
    int tillIndex = line.length();

    if (wsIndex > 0) {
      wsCount++;
      while (wsCount < index) {
        while (line.charAt(++wsIndex) == ' ') {
          if (wsIndex >= limit) {
            return null;
          }
        }
        fromIndex = wsIndex;
        wsIndex = line.indexOf(' ', wsIndex + 1);
        if (wsIndex <= 0) {
          tillIndex = line.length();
          break;
        } else {
          tillIndex = wsIndex;
        }
        wsCount++;
      }
    }
    return wsCount <= index ? line.substring(fromIndex, tillIndex).trim() : null;
  }

  private static boolean isSmapHeader(String line) {
    char firstChar = line.charAt(0);
    return ((firstChar >= '0' && firstChar <= '9') || (firstChar >= 'a' && firstChar <= 'f'));
  }

  // accessible for testing
  static void readEvents(BufferedReader br, List<SmapEntryEvent> events) throws IOException {
    String line = br.readLine();
    while (line != null) {
      if (!isSmapHeader(line)) {
        throw new IllegalStateException("Expected SMAP header line but got " + line);
      }
      SmapEntryEvent event = new SmapEntryEvent();
      events.add(event);
      SimpleParser parser = new SimpleParser(line);
      // parse header
      event.startAddress = parser.nextLongValue(16);
      if (event.startAddress == VSYSCALL_START_ADDRESS) {
        // vsyscall will always map to this region, but in case we ever do size calculations we
        // make the start
        // address 0x1000 less than the end address to keep relative sizing correct
        event.startAddress = -0x1000 - 1;
        event.endAddress = -1;
        parser.skipToNextValue(false);
      } else {
        event.endAddress = parser.nextLongValue(16);
      }
      event.perms = parser.nextStringValue();
      event.offset = parser.nextLongValue(16);
      event.dev = parser.nextStringValue();
      event.inodeID = (int) parser.nextLongValue(10);
      String pathname = parser.nextStringValue();
      event.pathname = pathname == null ? "" : pathname;
      // content lines follow
      while ((line = br.readLine()) != null) {
        if (isSmapHeader(line)) {
          // jump back to header parsing
          break;
        }
        parser = new SimpleParser(line);
        String key = parser.nextStringValue();
        if (key == null) {
          throw new IllegalStateException("Expected missing SMAP key in '" + line + "'");
        }
        switch (key) {
          case "Size:":
            event.size = parser.nextLongValue(10) * 1024;
            break;
          case "KernelPageSize:":
            event.kernelPageSize = parser.nextLongValue(10) * 1024;
            break;
          case "MMUPageSize:":
            event.mmuPageSize = parser.nextLongValue(10) * 1024;
            break;
          case "Rss:":
            event.rss = parser.nextLongValue(10) * 1024;
            break;
          case "Pss:":
            event.pss = parser.nextLongValue(10) * 1024;
            break;
          case "Pss_Dirty:":
            event.pssDirty = parser.nextLongValue(10) * 1024;
            break;
          case "Shared_Clean:":
            event.sharedClean = parser.nextLongValue(10) * 1024;
            break;
          case "Shared_Dirty:":
            event.sharedDirty = parser.nextLongValue(10) * 1024;
            break;
          case "Private_Clean:":
            event.privateClean = parser.nextLongValue(10) * 1024;
            break;
          case "Private_Dirty:":
            event.privateDirty = parser.nextLongValue(10) * 1024;
            break;
          case "Referenced:":
            event.referenced = parser.nextLongValue(10) * 1024;
            break;
          case "Anonymous:":
            event.anonymous = parser.nextLongValue(10) * 1024;
            break;
          case "KSM:":
            event.ksm = parser.nextLongValue(10) * 1024;
            break;
          case "LazyFree:":
            event.lazyFree = parser.nextLongValue(10) * 1024;
            break;
          case "AnonHugePages:":
            event.anonHugePages = parser.nextLongValue(10) * 1024;
            break;
          case "ShmemPmdMapped:":
            event.shmemPmdMapped = parser.nextLongValue(10) * 1024;
            break;
          case "FilePmdMapped:":
            event.filePmdMapped = parser.nextLongValue(10) * 1024;
            break;
          case "Shared_Hugetlb:":
            event.sharedHugetlb = parser.nextLongValue(10) * 1024;
            break;
          case "Private_Hugetlb:":
            event.privateHugetlb = parser.nextLongValue(10) * 1024;
            break;
          case "Swap:":
            event.swap = parser.nextLongValue(10) * 1024;
            break;
          case "SwapPss:":
            event.swapPss = parser.nextLongValue(10) * 1024;
            break;
          case "Locked:":
            event.locked = parser.nextLongValue(10) * 1024;
            break;
          case "THPeligible:":
            event.thpEligible = parser.nextLongValue(10) == 1;
            break;
          case "VmFlags:":
            event.vmFlags = parser.slurpStringValue();
            break;
          default:
            event.encounteredForeignKeys = true;
            break;
        }
      }
    }
  }

  @SuppressForbidden
  private static Map<Long, String> getAnnotatedRegions() {
    try {
      ObjectName objectName = new ObjectName("com.sun.management:type=DiagnosticCommand");
      MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();

      String[] emptyStringArgs = {};
      Object[] dcmdArgs = {emptyStringArgs};
      String[] signature = {String[].class.getName()};

      String[] lines =
          ((String) mbs.invoke(objectName, "systemMap", dcmdArgs, signature)).split("\n");
      Map<Long, String> annotatedRegions = new HashMap<>();

      // Java 24+ format is different from Java 23
      int javaVersion = JavaVirtualMachine.isJavaVersionAtLeast(24) ? 24 : 23;

      for (String line : lines) {
        AnnotatedRegion region = fromAnnotatedEntry(line, javaVersion);
        if (region != null) {
          annotatedRegions.put(region.startAddress, region.description);
        }
      }
      return annotatedRegions;
    } catch (Exception e) {
      log.debug("Failed to get annotated regions", e);
    }
    return Collections.emptyMap();
  }

  private void collectEvents(List<SmapEntryEvent> events) {
    try (BufferedReader br =
        new BufferedReader(new InputStreamReader(Files.newInputStream(smapsPath)), 64 * 1024)) {
      readEvents(br, events);
      Map<Long, String> regions = getAnnotatedRegions();
      for (SmapEntryEvent e : events) {
        e.nmtCategory = regions.getOrDefault(e.startAddress, "UNKNOWN");
      }
      log.debug("Collected {} smap entry events.", events.size());
    } catch (IOException e) {
      log.debug("Failed to read smap file", e);
    } catch (Exception e) {
      log.debug("Failed to parse smap file", e);
    }
  }
}
