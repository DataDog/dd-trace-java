package com.datadog.profiling.controller.openjdk.events;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

@Name("datadog.SmapEntry")
@Label("Smap Entry")
@Description("Entry from the smaps file for the JVM")
@Category("Datadog")
@StackTrace(false)
public class SmapEntryEvent {

  //todo maybe the labels should be explicitly what is used in smaps
  //while the description should be more human readable
  //this would make it easier to map the fields to the smaps file
  //and also make it easier to understand what the fields are
  //for someone who is not familiar with the smaps file

  @Label("Region Start Address")
  private final long startAddress;

  @Label("Region End Address")
  private final long endAddress;

  @Label("Region Permissions")
  private final String perms;

  @Label("Offset into mapping")
  private final long offset;

  @Label("Device Major ID")
  private final int majorID;

  @Label("Device Minor ID")
  private final int minorID;

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
  private final String[] vmFlags;

  public SmapEntryEvent(long startAddress, long endAddress, String perms, long offset, int majorID, int minorID, int inodeID, String pathname, long size, long kernelPageSize, long mmuPageSize, long rss, long pss, long sharedClean, long sharedDirty, long privateClean, long privateDirty, long referenced, long anonymous, long lazyFree, long anonHugePages, long shmemPmdMapped, long filePmdMapped, long sharedHugetlb, long privateHugetlb, long swap, long swapPss, long locked, boolean thpEligible, String[] vmFlags) {
    this.startAddress = startAddress;
    this.endAddress = endAddress;
    this.perms = perms;
    this.offset = offset;
    this.majorID = majorID;
    this.minorID = minorID;
    this.inodeID = inodeID;
    this.pathname = pathname;
    this.size = size;
    this.kernelPageSize = kernelPageSize;
    this.mmuPageSize = mmuPageSize;
    this.rss = rss;
    this.pss = pss;
    this.sharedClean = sharedClean;
    this.sharedDirty = sharedDirty;
    this.privateClean = privateClean;
    this.privateDirty = privateDirty;
    this.referenced = referenced;
    this.anonymous = anonymous;
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
  }
}
