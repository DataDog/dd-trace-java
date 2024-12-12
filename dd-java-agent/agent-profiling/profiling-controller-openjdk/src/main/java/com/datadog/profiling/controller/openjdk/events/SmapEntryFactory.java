package com.datadog.profiling.controller.openjdk.events;

import datadog.trace.api.Platform;
import datadog.trace.bootstrap.instrumentation.jfr.JfrHelper;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.io.File;
import java.io.FileNotFoundException;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
  private static final Pattern SYSTEM_MAP_ENTRY_PATTERN =
      Pattern.compile(
          "([0-9a-fA-Fx]+)\\s+-\\s+([0-9a-fA-Fx]+)\\s+(\\d+)\\s+(\\S+)\\s+(\\S+)(?:\\s+(.*))?");
  private static final String VSYSCALL_START_ADDRESS = "ffffffffff600000";
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
    if (REGISTERED.compareAndSet(false, true) && Platform.isLinux()) {
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
          Matcher matcher = SYSTEM_MAP_ENTRY_PATTERN.matcher(line);
          if (matcher.matches()) {
            long startAddress;
            if (matcher.group(1).equals("0x" + VSYSCALL_START_ADDRESS)) {
              // See how smap entry parsing is done for vsyscall
              startAddress = -0x1000 - 1;
            } else {
              startAddress = Long.decode(matcher.group(1));
            }
            String description = matcher.group(6);
            annotatedRegions.put(startAddress, description);
            if (description.isEmpty()) {
              annotatedRegions.put(startAddress, "UNDEFINED");
            } else if (description.startsWith("STACK")) {
              annotatedRegions.put(startAddress, "STACK");
            } else if (description.startsWith("[") || description.startsWith("/")) {
              annotatedRegions.put(startAddress, "SYSTEM");
            } else {
              annotatedRegions.put(startAddress, description.split("\\s+")[0]);
            }
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

  @SuppressForbidden // split with one-char String use a fast-path without regex usage
  static List<? extends Event> collectEvents() {
    if (!SMAP_ENTRY_EVENT.isEnabled()) {
      return Collections.emptyList();
    }

    List<Event> events = new ArrayList<>();

    long startAddress;
    long endAddress;
    String perms;
    long offset;
    String dev;
    int inode;
    String pathname = "";

    long size = 0;
    long kernelPageSize = 0;
    long mmuPageSize = 0;
    long rss = 0;
    long pss = 0;
    long pssDirty = 0;
    long sharedClean = 0;
    long sharedDirty = 0;
    long privateClean = 0;
    long privateDirty = 0;
    long referenced = 0;
    long anonymous = 0;
    long ksm = 0;
    long lazyFree = 0;
    long anonHugePages = 0;
    long shmemPmdMapped = 0;
    long filePmdMapped = 0;
    long sharedHugetlb = 0;
    long privateHugetlb = 0;
    long swap = 0;
    long swapPss = 0;
    long locked = 0;

    boolean thpEligible = false;
    String vmFlags = null;

    HashMap<Long, String> annotatedRegions = getAnnotatedRegions();

    try (Scanner scanner = new Scanner(new File("/proc/self/smaps"))) {
      while (scanner.hasNextLine()) {
        boolean encounteredForeignKeys = false;
        String[] addresses = scanner.next().split("-");
        if (!addresses[0].equals(VSYSCALL_START_ADDRESS)) {
          startAddress = Long.parseLong(addresses[0], 16);
          endAddress = Long.parseLong(addresses[1], 16);
        } else {
          // vsyscall will always map to this region, but in case we ever do size calculations we
          // make the start
          // address 0x1000 less than the end address to keep relative sizing correct
          startAddress = -0x1000 - 1;
          endAddress = -1;
        }
        perms = scanner.next();
        offset = scanner.nextLong(16);
        dev = scanner.next();
        inode = scanner.nextInt();
        if (scanner.hasNextLine()) {
          pathname = scanner.nextLine().trim();
        } else {
          pathname = "";
        }

        boolean reachedEnd = false;
        while (!reachedEnd) {
          String key = scanner.next();
          switch (key) {
            case "Size:":
              size = scanner.nextLong() * 1024;
              scanner.next();
              break;
            case "KernelPageSize:":
              kernelPageSize = scanner.nextLong() * 1024;
              scanner.next();
              break;
            case "MMUPageSize:":
              mmuPageSize = scanner.nextLong() * 1024;
              scanner.next();
              break;
            case "Rss:":
              rss = scanner.nextLong() * 1024;
              scanner.next();
              break;
            case "Pss:":
              pss = scanner.nextLong() * 1024;
              scanner.next();
              break;
            case "Pss_Dirty:":
              pssDirty = scanner.nextLong() * 1024;
              scanner.next();
              break;
            case "Shared_Clean:":
              sharedClean = scanner.nextLong() * 1024;
              scanner.next();
              break;
            case "Shared_Dirty:":
              sharedDirty = scanner.nextLong() * 1024;
              scanner.next();
              break;
            case "Private_Clean:":
              privateClean = scanner.nextLong() * 1024;
              scanner.next();
              break;
            case "Private_Dirty:":
              privateDirty = scanner.nextLong() * 1024;
              scanner.next();
              break;
            case "Referenced:":
              referenced = scanner.nextLong() * 1024;
              scanner.next();
              break;
            case "Anonymous:":
              anonymous = scanner.nextLong() * 1024;
              scanner.next();
              break;
            case "KSM:":
              ksm = scanner.nextLong() * 1024;
              scanner.next();
              break;
            case "LazyFree:":
              lazyFree = scanner.nextLong() * 1024;
              scanner.next();
              break;
            case "AnonHugePages:":
              anonHugePages = scanner.nextLong() * 1024;
              scanner.next();
              break;
            case "ShmemPmdMapped:":
              shmemPmdMapped = scanner.nextLong() * 1024;
              scanner.next();
              break;
            case "FilePmdMapped:":
              filePmdMapped = scanner.nextLong() * 1024;
              scanner.next();
              break;
            case "Shared_Hugetlb:":
              sharedHugetlb = scanner.nextLong() * 1024;
              scanner.next();
              break;
            case "Private_Hugetlb:":
              privateHugetlb = scanner.nextLong() * 1024;
              scanner.next();
              break;
            case "Swap:":
              swap = scanner.nextLong() * 1024;
              scanner.next();
              break;
            case "SwapPss:":
              swapPss = scanner.nextLong() * 1024;
              scanner.next();
              break;
            case "Locked:":
              locked = scanner.nextLong() * 1024;
              scanner.next();
              break;
            case "THPeligible:":
              thpEligible = scanner.nextInt() == 1;
              break;
            case "VmFlags:":
              scanner.skip("\\s+");
              vmFlags = scanner.nextLine();
              reachedEnd = true;
              break;
            default:
              encounteredForeignKeys = true;
              break;
          }
        }

        String nmtCategory;
        if (annotatedRegions != null && annotatedRegions.containsKey(startAddress)) {
          nmtCategory = annotatedRegions.get(startAddress);
        } else {
          nmtCategory = "UNKNOWN";
        }
        events.add(
            new SmapEntryEvent(
                startAddress,
                endAddress,
                perms,
                offset,
                dev,
                inode,
                pathname,
                size,
                kernelPageSize,
                mmuPageSize,
                rss,
                pss,
                pssDirty,
                sharedClean,
                sharedDirty,
                privateClean,
                privateDirty,
                referenced,
                anonymous,
                ksm,
                lazyFree,
                anonHugePages,
                shmemPmdMapped,
                filePmdMapped,
                sharedHugetlb,
                privateHugetlb,
                swap,
                swapPss,
                locked,
                thpEligible,
                vmFlags,
                encounteredForeignKeys,
                nmtCategory));
      }
      return events;
    } catch (FileNotFoundException e) {
      return List.of(new SmapParseErrorEvent(ErrorReason.SMAP_FILE_NOT_FOUND));
    } catch (Exception e) {
      return List.of(new SmapParseErrorEvent(ErrorReason.SMAP_PARSING_ERROR));
    }
  }
}
