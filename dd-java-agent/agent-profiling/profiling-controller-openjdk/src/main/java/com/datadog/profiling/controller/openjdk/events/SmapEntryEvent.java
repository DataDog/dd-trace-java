package com.datadog.profiling.controller.openjdk.events;

import jdk.jfr.Category;
import jdk.jfr.DataAmount;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.EventType;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.Period;
import jdk.jfr.StackTrace;

@Name("datadog.SmapEntry")
@Label("Smap Entry")
@Description("Entry from the smaps file for the JVM")
@Category("Datadog")
@Period("beginChunk")
@StackTrace(false)
public class SmapEntryEvent extends Event {

  @Label("Region Start Address")
  long startAddress;

  @Label("Region End Address")
  long endAddress;

  @Label("Region Permissions")
  String perms;

  @Label("Offset into mapping")
  long offset;

  @Label("Device")
  String dev;

  @Label("INode ID")
  int inodeID;

  @Label("Path associated with mapping")
  String pathname;

  @Label("Mapping Size")
  long size;

  @Label("Page Size")
  long kernelPageSize;

  @Label("Memory Management Unit Page Size")
  long mmuPageSize;

  @Label("Resident Set Size")
  @DataAmount
  long rss;

  @Label("Proportional Set Size")
  @DataAmount
  long pss;

  @Label("Dirty Proportional Set Size")
  @DataAmount
  long pssDirty;

  @Label("Shared Clean Pages")
  long sharedClean;

  @Label("Shared Dirty Pages")
  @DataAmount
  long sharedDirty;

  @Label("Private Clean Pages")
  @DataAmount
  long privateClean;

  @Label("Private Dirty Pages")
  @DataAmount
  long privateDirty;

  @Label("Referenced Memory")
  long referenced;

  @Label("Anonymous Memory")
  @DataAmount
  long anonymous;

  @Label("Kernel Same-page Merging")
  @DataAmount
  long ksm;

  @Label("Lazily Freed Memory")
  @DataAmount
  long lazyFree;

  @Label("Anon Huge Pages")
  @DataAmount
  long anonHugePages;

  @Label("Shared Memory Huge Pages")
  @DataAmount
  long shmemPmdMapped;

  @Label("Page Cache Huge Pages")
  @DataAmount
  long filePmdMapped;

  @Label("Shared Huge Pages")
  @DataAmount
  long sharedHugetlb;

  @Label("Private Huge Pages")
  @DataAmount
  long privateHugetlb;

  @Label("Swap Size")
  @DataAmount
  long swap;

  @Label("Proportional Swap Size")
  @DataAmount
  long swapPss;

  @Label("Locked Memory")
  @DataAmount
  long locked;

  @Label("THP Eligible")
  boolean thpEligible;

  @Label("VM Flags")
  String vmFlags;

  @Label("Encountered foreign keys")
  boolean encounteredForeignKeys;

  @Label("NMT Category")
  String nmtCategory;

  public SmapEntryEvent() {
    startAddress = 0;
    endAddress = 0;
    perms = "";
    offset = 0;
    dev = "";
    inodeID = 0;
    pathname = "";
    size = 0;
    kernelPageSize = 0;
    mmuPageSize = 0;
    rss = 0;
    pss = 0;
    pssDirty = 0;
    sharedClean = 0;
    sharedDirty = 0;
    privateClean = 0;
    privateDirty = 0;
    referenced = 0;
    anonymous = 0;
    ksm = 0;
    lazyFree = 0;
    anonHugePages = 0;
    shmemPmdMapped = 0;
    filePmdMapped = 0;
    sharedHugetlb = 0;
    privateHugetlb = 0;
    swap = 0;
    swapPss = 0;
    locked = 0;
    thpEligible = false;
    vmFlags = "";
    encounteredForeignKeys = false;
    nmtCategory = "";
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

  public static void emit() {
    if (!EventType.getEventType(AggregatedSmapEntryEvent.class).isEnabled()) {
      SmapEntryFactory.collectEvents().forEach(Event::commit);
    } else {
    }
  }

  public long getRss() {
    return this.rss;
  }

  public String getNmtCategory() {
    return this.nmtCategory;
  }
}
