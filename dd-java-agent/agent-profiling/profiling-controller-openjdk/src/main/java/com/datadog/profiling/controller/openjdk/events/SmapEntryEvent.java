package com.datadog.profiling.controller.openjdk.events;

import com.datadog.profiling.controller.openjdk.OpenJdkController;
import datadog.trace.bootstrap.instrumentation.jfr.JfrHelper;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.io.File;
import java.io.FileNotFoundException;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Enabled;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.Period;
import jdk.jfr.StackTrace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Name("datadog.SmapEntry")
@Label("Smap Entry")
@Description("Entry from the smaps file for the JVM")
@Category("Datadog")
@Period("beginChunk")
@Enabled
@StackTrace(false)
public class SmapEntryEvent extends Event {
  private static final AtomicBoolean registered = new AtomicBoolean(false);
  private static boolean annotatedMapsAvailable;
  private static final Logger log = LoggerFactory.getLogger(OpenJdkController.class);
  private static final String VSYSCALL_START_ADDRESS = "ffffffffff600000";

  @Label("Region Start Address")
  private final long startAddress;

  @Label("Region End Address")
  private final long endAddress;

  @Label("Region Permissions")
  private final String perms;

  @Label("Offset into mapping")
  private final long offset;

  @Label("Device")
  private final String dev;

  @Label("INode ID")
  private final int inodeID;

  @Label("Path associated with mapping")
  private final String pathname;

  @Label("Mapping Size")
  private final long size;

  @Label("Page Size")
  private final long kernelPageSize;

  @Label("Memory Management Unit Page Size")
  private final long mmuPageSize;

  @Label("Resident Set Size")
  private final long rss;

  @Label("Proportional Set Size")
  private final long pss;

  @Label("Dirty Proportional Set Size")
  private final long pssDirty;

  @Label("Shared Clean Pages")
  private final long sharedClean;

  @Label("Shared Dirty Pages")
  private final long sharedDirty;

  @Label("Private Clean Pages")
  private final long privateClean;

  @Label("Private Dirty Pages")
  private final long privateDirty;

  @Label("Referenced Memory")
  private final long referenced;

  @Label("Anonymous Memory")
  private final long anonymous;

  @Label("Kernel Same-page Merging")
  private final long ksm;

  @Label("Lazily Freed Memory")
  private final long lazyFree;

  @Label("Anon Huge Pages")
  private final long anonHugePages;

  @Label("Shared Memory Huge Pages")
  private final long shmemPmdMapped;

  @Label("Page Cache Huge Pages")
  private final long filePmdMapped;

  @Label("Shared Huge Pages")
  private final long sharedHugetlb;

  @Label("Private Huge Pages")
  private final long privateHugetlb;

  @Label("Swap Size")
  private final long swap;

  @Label("Proportional Swap Size")
  private final long swapPss;

  @Label("Locked Memory")
  private final long locked;

  @Label("THP Eligible")
  private final boolean thpEligible;

  @Label("VM Flags")
  private final String vmFlags;

  @Label("Encountered foreign keys")
  private final boolean encounteredForeignKeys;

  @Label("NMT Category")
  private final String nmtCategory;

  private enum ErrorReason {
    PARSING_ERROR,
    SMAP_FILE_NOT_FOUND,
  }

  private static class SmapParseErrorEvent extends Event {
    @Label("Reason")
    private final ErrorReason reason;

    public SmapParseErrorEvent(ErrorReason reason) {
      this.reason = reason;
    }
  }

  public SmapEntryEvent(
      long startAddress,
      long endAddress,
      String perms,
      long offset,
      String dev,
      int inodeID,
      String pathname,
      long size,
      long kernelPageSize,
      long mmuPageSize,
      long rss,
      long pss,
      long pssDirty,
      long sharedClean,
      long sharedDirty,
      long privateClean,
      long privateDirty,
      long referenced,
      long anonymous,
      long ksm,
      long lazyFree,
      long anonHugePages,
      long shmemPmdMapped,
      long filePmdMapped,
      long sharedHugetlb,
      long privateHugetlb,
      long swap,
      long swapPss,
      long locked,
      boolean thpEligible,
      String vmFlags,
      boolean encounteredForeignKeys,
      String nmtCategory) {
    this.startAddress = startAddress;
    this.endAddress = endAddress;
    this.perms = perms;
    this.offset = offset;
    this.dev = dev;
    this.inodeID = inodeID;
    this.pathname = pathname;
    this.size = size;
    this.kernelPageSize = kernelPageSize;
    this.mmuPageSize = mmuPageSize;
    this.rss = rss;
    this.pss = pss;
    this.pssDirty = pssDirty;
    this.sharedClean = sharedClean;
    this.sharedDirty = sharedDirty;
    this.privateClean = privateClean;
    this.privateDirty = privateDirty;
    this.referenced = referenced;
    this.anonymous = anonymous;
    this.ksm = ksm;
    this.lazyFree = lazyFree;
    this.anonHugePages = anonHugePages;
    this.shmemPmdMapped = shmemPmdMapped;
    this.filePmdMapped = filePmdMapped;
    this.sharedHugetlb = sharedHugetlb;
    this.privateHugetlb = privateHugetlb;
    this.swap = swap;
    this.swapPss = swapPss;
    this.locked = locked;
    this.thpEligible = thpEligible;
    this.vmFlags = vmFlags;
    this.encounteredForeignKeys = encounteredForeignKeys;
    this.nmtCategory = nmtCategory;
  }

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
        Pattern pattern =
            Pattern.compile(
                "([0-9a-fA-Fx]+)\\s+-\\s+([0-9a-fA-Fx]+)\\s+(\\d+)\\s+(\\S+)\\s+(\\S+)(?:\\s+(.*))?");

        for (String line : lines) {
          Matcher matcher = pattern.matcher(line);
          if (matcher.matches()) {
            long startAddress = Long.decode(matcher.group(1));
            String description = matcher.group(6);
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
        return null;
      }
    }
    return null;
  }

  @SuppressForbidden // split with one-char String use a fast-path without regex usage
  public static void emit() {
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
              size = scanner.nextLong();
              scanner.next();
              break;
            case "KernelPageSize:":
              kernelPageSize = scanner.nextLong();
              scanner.next();
              break;
            case "MMUPageSize:":
              mmuPageSize = scanner.nextLong();
              scanner.next();
              break;
            case "Rss:":
              rss = scanner.nextLong();
              scanner.next();
              break;
            case "Pss:":
              pss = scanner.nextLong();
              scanner.next();
              break;
            case "Pss_Dirty:":
              pssDirty = scanner.nextLong();
              scanner.next();
              break;
            case "Shared_Clean:":
              sharedClean = scanner.nextLong();
              scanner.next();
              break;
            case "Shared_Dirty:":
              sharedDirty = scanner.nextLong();
              scanner.next();
              break;
            case "Private_Clean:":
              privateClean = scanner.nextLong();
              scanner.next();
              break;
            case "Private_Dirty:":
              privateDirty = scanner.nextLong();
              scanner.next();
              break;
            case "Referenced:":
              referenced = scanner.nextLong();
              scanner.next();
              break;
            case "Anonymous:":
              anonymous = scanner.nextLong();
              scanner.next();
              break;
            case "KSM:":
              ksm = scanner.nextLong();
              scanner.next();
              break;
            case "LazyFree:":
              lazyFree = scanner.nextLong();
              scanner.next();
              break;
            case "AnonHugePages:":
              anonHugePages = scanner.nextLong();
              scanner.next();
              break;
            case "ShmemPmdMapped:":
              shmemPmdMapped = scanner.nextLong();
              scanner.next();
              break;
            case "FilePmdMapped:":
              filePmdMapped = scanner.nextLong();
              scanner.next();
              break;
            case "Shared_Hugetlb:":
              sharedHugetlb = scanner.nextLong();
              scanner.next();
              break;
            case "Private_Hugetlb:":
              privateHugetlb = scanner.nextLong();
              scanner.next();
              break;
            case "Swap:":
              swap = scanner.nextLong();
              scanner.next();
              break;
            case "SwapPss:":
              swapPss = scanner.nextLong();
              scanner.next();
              break;
            case "Locked:":
              locked = scanner.nextLong();
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
          //          System.out.println(annotatedRegions);
          nmtCategory = "UNKNOWN";
        }
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
                nmtCategory)
            .commit();
      }
    } catch (FileNotFoundException e) {
      log.info("Could not read /proc/self/smaps");
      new SmapParseErrorEvent(ErrorReason.SMAP_FILE_NOT_FOUND).commit();
    } catch (Exception e) {
      log.info("A runtime error occurred while parsing /proc/self/smaps");
      new SmapParseErrorEvent(ErrorReason.PARSING_ERROR).commit();
    }
  }

  public static void register() {
    // Make sure the periodic event is registered only once
    if (registered.compareAndSet(false, true)) {
      JfrHelper.addPeriodicEvent(SmapEntryEvent.class, SmapEntryEvent::emit);
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
  }
}
