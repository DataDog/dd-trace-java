package com.datadog.profiling.controller.openjdk.events;

import datadog.trace.api.Platform;
import datadog.trace.bootstrap.instrumentation.jfr.JfrHelper;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.lang.management.ManagementFactory;
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
  private static final String VSYSCALL_START_ADDRESS = "ffffffffff600000";
  private static final char[] PREFIX = {'0', 'x'};
  private static final SmapEntryEvent SMAP_ENTRY_EVENT = new SmapEntryEvent();

  private enum ErrorReason {
    SMAP_PARSING_ERROR,
    SMAP_FILE_NOT_FOUND,
    VM_MAP_UNAVAILABLE,
    VM_MAP_PARSING_ERROR,
  }

  private static class SmapHeader {
    long startAddress;
    long endAddress;
    String perms;
    long offset;
    String dev;
    int inode;
    String pathname;

    public SmapHeader(
        long endAddress,
        long startAddress,
        String perms,
        long offset,
        String dev,
        int inode,
        String pathname) {
      this.endAddress = endAddress;
      this.startAddress = startAddress;
      this.perms = perms;
      this.offset = offset;
      this.dev = dev;
      this.inode = inode;
      this.pathname = pathname;
    }
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
        boolean arrivedAtMappings = false;

        for (String line : lines) {
          // The output begins with a legend followed by a `size prot offset What` table header
          if (!arrivedAtMappings) {
            if (line.startsWith("size")) {
              arrivedAtMappings = true;
            }
            continue;
          } else {
            // The final line contains the total number of mappings and their net vsize
            if (line.startsWith("Total")) {
              break;
            }
          }

          String[] segments = line.split("\\s+");
          long startAddress;
          if (segments[0].equals("0x" + VSYSCALL_START_ADDRESS)) {
            startAddress = -0x1000 - 1;
          } else {
            startAddress = Long.decode(segments[0]);
          }

          if (segments.length >= 7) {
            // The schema is derived from an example output, see
            // https://gist.github.com/MattAlp/5618219d5719e773d4c1fedfe9ff399d
            // It may need to be updated in the future
            String description = segments[6];
            if (description.startsWith("STACK")) {
              annotatedRegions.put(startAddress, "STACK");
            } else if (description.startsWith("[") || description.startsWith("/")) {
              annotatedRegions.put(startAddress, "SYSTEM");
            } else if (description.startsWith("CDS")) {
              annotatedRegions.put(startAddress, "CDS");
            } else if (description.startsWith("INTERN")) {
              annotatedRegions.put(startAddress, "INTERN");
            } else {
              annotatedRegions.put(startAddress, description.toUpperCase());
            }
          } else {
            annotatedRegions.put(startAddress, "UNDEFINED");
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

  private static long readLong(BufferedReader br, char delimiter, int base) throws IOException {
    long number = 0;
    char ch;
    while ((ch = (char) br.read()) != delimiter) {
      number *= base;
      number += Character.digit(ch, base);
    }

    return number;
  }

  private static int readInt(BufferedReader br, char delimiter, int base) throws IOException {
    int number = 0;
    char ch;
    while ((ch = (char) br.read()) != delimiter) {
      number *= base;
      number += Character.digit(ch, base);
    }
    return number;
  }

  private static void readUntil(BufferedReader br, char target) throws IOException {
    char ch = '\n';
    while (ch != target) {
      ch = (char) br.read();
    }
  }

  private static String readStringUntil(BufferedReader br, char target) throws IOException {
    StringBuilder sb = new StringBuilder();
    char ch = '\n';
    while (ch != target) {
      ch = (char) br.read();
      sb.append(ch);
    }
    return sb.toString();
  }

  private static SmapHeader parseLine(BufferedReader br) throws IOException {
    long startAddress;
    long endAddress;
    String perms;
    long offset;
    String dev;
    int inode;
    String pathname;

    startAddress = readLong(br, '-', 16);
    if (startAddress == 0xffffffffff600000L) {
      startAddress = -0x1000 - 1;
      endAddress = -1;
      readUntil(br, ' ');
    } else {
      endAddress = readLong(br, ' ', 16);
    }
    perms = readStringUntil(br, ' ');
    offset = readLong(br, ' ', 16);
    dev = readStringUntil(br, ' ');
    inode = readInt(br, ' ', 10);
    pathname = readStringUntil(br, '\n').trim();

    return new SmapHeader(endAddress, startAddress, perms, offset, dev, inode, pathname);
  }

  static List<? extends Event> collectEvents() {
    if (!SMAP_ENTRY_EVENT.isEnabled()) {
      return Collections.emptyList();
    }

    List<Event> events = new ArrayList<>();

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
    String vmFlags;

    HashMap<Long, String> annotatedRegions = getAnnotatedRegions();
    try (BufferedReader reader = new BufferedReader(new FileReader("/proc/self/smaps"))) {
      String line;
      StringBuilder buffer = new StringBuilder();
      while (true) { // fix this for later
        boolean encounteredForeignKeys = false;
        SmapHeader sh = parseLine(reader);

        if (sh.startAddress == 420) {
          break;
        }

        while (true) {
          buffer.setLength(0);
          char[] attributedChars = reader.readLine().toCharArray();
          int j = 0;
          while (attributedChars[j] != ':') {
            buffer.append(attributedChars[j]);
            j++;
          }
          String attributeName = buffer.toString();
          j++;
          buffer.setLength(0);

          if ("VmFlags".equals(attributeName)) {
            while (j < attributedChars.length) {
              buffer.append(attributedChars[j]);
              j++;
            }
            vmFlags = buffer.toString();
            break;
          } else {
            while (attributedChars[j] == ' ') {
              j++;
            }
            while (j < attributedChars.length && attributedChars[j] != ' ') {
              buffer.append(attributedChars[j]);
              j++;
            }

            if ("ThpEligible".equals(attributeName)) {
              thpEligible = buffer.toString().equals("1");
            } else if ("ProtectionKey".equals(attributeName)) {
              // Original event did not include protection key attribute, so skipping for now
              encounteredForeignKeys = true;
            } else {
              switch (attributeName) {
                case "Size":
                  size = Long.decode(buffer.toString()) * 1024;
                  break;
                case "KernelPageSize":
                  kernelPageSize = Long.decode(buffer.toString()) * 1024;
                  break;
                case "MMUPageSize":
                  mmuPageSize = Long.decode(buffer.toString()) * 1024;
                  break;
                case "Rss":
                  rss = Long.decode(buffer.toString()) * 1024;
                  break;
                case "Pss":
                  pss = Long.decode(buffer.toString()) * 1024;
                  break;
                case "Pss_Dirty":
                  pssDirty = Long.decode(buffer.toString()) * 1024;
                  break;
                case "Shared_Clean":
                  sharedClean = Long.decode(buffer.toString()) * 1024;
                  break;
                case "Shared_Dirty":
                  sharedDirty = Long.decode(buffer.toString()) * 1024;
                  break;
                case "Private_Clean":
                  privateClean = Long.decode(buffer.toString()) * 1024;
                  break;
                case "Private_Dirty":
                  privateDirty = Long.decode(buffer.toString()) * 1024;
                  break;
                case "Referenced":
                  referenced = Long.decode(buffer.toString()) * 1024;
                  break;
                case "Anonymous":
                  anonymous = Long.decode(buffer.toString()) * 1024;
                  break;
                case "KSM":
                  ksm = Long.decode(buffer.toString()) * 1024;
                  break;
                case "LazyFree":
                  lazyFree = Long.decode(buffer.toString()) * 1024;
                  break;
                case "AnonHugePages":
                  anonHugePages = Long.decode(buffer.toString()) * 1024;
                  break;
                case "ShmemPmdMapped":
                  shmemPmdMapped = Long.decode(buffer.toString()) * 1024;
                  break;
                case "FilePmdMapped":
                  filePmdMapped = Long.decode(buffer.toString()) * 1024;
                  break;
                case "Shared_Hugetlb":
                  sharedHugetlb = Long.decode(buffer.toString()) * 1024;
                  break;
                case "Private_Hugetlb":
                  privateHugetlb = Long.decode(buffer.toString()) * 1024;
                  break;
                case "Swap":
                  swap = Long.decode(buffer.toString()) * 1024;
                  break;
                case "SwapPss":
                  swapPss = Long.decode(buffer.toString()) * 1024;
                  break;
                case "Locked":
                  locked = Long.decode(buffer.toString()) * 1024;
                  break;
                default:
                  encounteredForeignKeys = true;
                  break;
              }
            }
          }
        }

        String nmtCategory;
        if (annotatedRegions != null && annotatedRegions.containsKey(sh.startAddress)) {
          nmtCategory = annotatedRegions.get(sh.startAddress);
        } else {
          nmtCategory = "UNKNOWN";
        }
        events.add(
            new SmapEntryEvent(
                sh.startAddress,
                sh.endAddress,
                sh.perms,
                sh.offset,
                sh.dev,
                sh.inode,
                sh.pathname,
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
      e.printStackTrace();
      return List.of(new SmapParseErrorEvent(ErrorReason.SMAP_PARSING_ERROR));
    }
  }
}
