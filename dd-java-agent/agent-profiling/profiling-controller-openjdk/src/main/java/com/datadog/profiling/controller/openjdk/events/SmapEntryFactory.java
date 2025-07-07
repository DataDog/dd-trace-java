package com.datadog.profiling.controller.openjdk.events;

import datadog.environment.OperatingSystem;
import datadog.trace.bootstrap.instrumentation.jfr.JfrHelper;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SmapEntryFactory {

  private static final Logger log = LoggerFactory.getLogger(SmapEntryFactory.class);

  private static final AtomicBoolean REGISTERED = new AtomicBoolean();
  private static boolean annotatedMapsAvailable;
  private static final String VSYSCALL_START_ADDRESS_STR = "ffffffffff600000";
  private static final long VSYSCALL_START_ADDRESS = 0xffffffffff600000L;
  private static final SmapEntryEvent SMAP_ENTRY_EVENT = new SmapEntryEvent();

  private enum ErrorReason {
    SMAP_PARSING_ERROR,
    SMAP_FILE_NOT_FOUND,
    VM_MAP_UNAVAILABLE,
    VM_MAP_PARSING_ERROR,
  }

  @Category("Datadog")
  @Name("datadog.SmapParseErrorEvent")
  @Label("Smap Parsing Error")
  @StackTrace(false)
  private static class SmapParseErrorEvent extends Event {
    @Label("Reason")
    private final String reason;

    public SmapParseErrorEvent(ErrorReason reason) {
      this.reason = reason.toString();
    }
  }

  public static void registerEvents() {
    // Make sure the periodic event is registered only once
    if (REGISTERED.compareAndSet(false, true) && OperatingSystem.isLinux()) {
      // Only one of these should ever be enabled at the same time
      JfrHelper.addPeriodicEvent(SmapEntryEvent.class, SmapEntryEvent::emit);
      JfrHelper.addPeriodicEvent(AggregatedSmapEntryEvent.class, AggregatedSmapEntryEvent::emit);
      try {
        ObjectName objectName = new ObjectName("com.sun.management:type=DiagnosticCommand");
        MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();

        annotatedMapsAvailable =
            Arrays.stream(mbs.getMBeanInfo(objectName).getOperations())
                .anyMatch(x -> x.getName().equals("systemMap"));
      } catch (Exception e) {
        annotatedMapsAvailable = false;
      }
    }
    if (annotatedMapsAvailable) {
      log.debug("Smap entry events registered successfully");
    } else {
      log.warn("Smap entry events could not be registered due to missing systemMap operation");
    }
  }

  static class AnnotatedRegion {
    final long startAddress;
    final String description;

    AnnotatedRegion(long startAddress, String description) {
      this.startAddress = startAddress;
      this.description = description;
    }
  }

  static AnnotatedRegion fromAnnotatedEntry(String line) {
    boolean isRegion = line.startsWith("0x");
    if (isRegion) {
      // 0x0000000420000000 - 0x000000043b000000   452984832  rw-p 00000000    JAVAHEAP
      boolean isVsyscall =
          line.startsWith("0x" + VSYSCALL_START_ADDRESS_STR); // can't be parsed to Long safely(?)
      long startAddress = -1;
      int dashIndex = line.indexOf('-');
      if (dashIndex > 0) {
        startAddress = isVsyscall ? -0x1000 - 1 : Long.decode(line.substring(0, dashIndex - 1));
        String description = extractElement(line, 4, dashIndex + 1);
        if (description == null || description.isEmpty()) {
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
    if (wsIndex > 0) {
      wsCount++;
      while (wsCount < index) {
        while (line.charAt(++wsIndex) == ' ') {}
        wsIndex = line.indexOf(' ', wsIndex + 1);
        if (wsIndex <= 0) {
          break;
        }
        wsCount++;
      }
    }
    return wsCount == index ? line.substring(wsIndex + 1).trim() : null;
  }

  @SuppressForbidden
  private static HashMap<Long, String> getAnnotatedRegions() {
    if (annotatedMapsAvailable) {
      try {
        ObjectName objectName = new ObjectName("com.sun.management:type=DiagnosticCommand");
        MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();

        String[] emptyStringArgs = {};
        Object[] dcmdArgs = {emptyStringArgs};
        String[] signature = {String[].class.getName()};

        String[] lines =
            ((String) mbs.invoke(objectName, "systemMap", dcmdArgs, signature)).split("\n");
        HashMap<Long, String> annotatedRegions = new HashMap<>();

        for (String line : lines) {
          AnnotatedRegion region = fromAnnotatedEntry(line);
          if (region != null) {
            annotatedRegions.put(region.startAddress, region.description);
          }
        }
        return annotatedRegions;
      } catch (Exception e) {
        new SmapParseErrorEvent(ErrorReason.VM_MAP_PARSING_ERROR).commit();
        return null;
      }
    } else {
      new SmapParseErrorEvent(ErrorReason.VM_MAP_UNAVAILABLE).commit();
      return null;
    }
  }

  private static boolean isSmapHeader(String line) {
    char firstChar = line.charAt(0);
    return ((firstChar >= '0' && firstChar <= '9') || (firstChar >= 'a' && firstChar <= 'f'));
  }

  static List<SmapEntryEvent> readEvents(BufferedReader br) throws IOException {
    List<SmapEntryEvent> events = new ArrayList<>();

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
    return events;
  }

  static List<? extends Event> collectEvents() {
    if (!SMAP_ENTRY_EVENT.isEnabled()) {
      return Collections.emptyList();
    }

    try (BufferedReader br =
        new BufferedReader(
            new InputStreamReader(Files.newInputStream(Paths.get("/proc/self/smaps"))),
            64 * 1024)) {
      List<SmapEntryEvent> events = readEvents(br);
      HashMap<Long, String> annotatedRegions = getAnnotatedRegions();
      events.forEach(
          e -> {
            String category =
                annotatedRegions != null ? annotatedRegions.get(e.startAddress) : null;
            e.nmtCategory = category != null ? category : "UNKNOWN";
          });
      return events;
    } catch (IOException e) {
      return List.of(new SmapParseErrorEvent(ErrorReason.SMAP_FILE_NOT_FOUND));
    } catch (Exception e) {
      return List.of(new SmapParseErrorEvent(ErrorReason.SMAP_PARSING_ERROR));
    }
  }
}
