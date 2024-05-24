package com.datadog.profiling.controller.openjdk;

import com.datadog.profiling.controller.openjdk.events.SmapEntryEvent;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

public class SmapsUtil {
  // todo uninstall copilot and rewrite this myself
  public static void recordSmapEvents(String path) throws IOException {
    BufferedReader br = Files.newBufferedReader(Paths.get(path));
    String l;
    long startAddress = 0;
    long endAddress = 0;
    String perms = null;
    long offset = 0;
    String major = null;
    int inode = 0;
    String pathname = null;

    long size = 0;
    long kernelPageSize = 0;
    long mmuPageSize = 0;
    long rss = 0;
    long pss = 0;
    long sharedClean = 0;
    long sharedDirty = 0;
    long privateClean = 0;
    long privateDirty = 0;
    long referenced = 0;
    long anonymous = 0;
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
    String[] vmFlags = null;

    do {
      l = br.readLine();
      if (l != null) {
        String[] parts = l.split("\\s+");
        if (parts.length < 6) {
          continue;
        }
        String[] address = parts[0].split("-");
        startAddress = Long.parseLong(address[0], 16);
        endAddress = Long.parseLong(address[1], 16);
        perms = parts[1];
        offset = Long.parseLong(parts[2], 16);
        major = parts[3];

        inode = Integer.parseInt(parts[4]);
        pathname = parts[5];
      }
      for (int i = 0; i < 22; i++) {
        l = br.readLine();
        if (l == null) {
          continue;
        }
        String[] kvs = l.split(":\\s+");
        switch (kvs[0]) {
          case "VmFlags":
            vmFlags = kvs[1].split(" ");
            break;
          case "THPeligible":
            thpEligible = Integer.parseInt(kvs[1]) == 1;
            break;
          case "Size":
            size = Long.parseLong(kvs[1].split(" ")[0]);
            break;
          case "KernelPageSize":
            kernelPageSize = Long.parseLong(kvs[1].split(" ")[0]);
            break;
          case "MMUPageSize":
            mmuPageSize = Long.parseLong(kvs[1].split(" ")[0]);
            break;
          case "Rss":
            rss = Long.parseLong(kvs[1].split(" ")[0]);
            break;
          case "Pss":
            pss = Long.parseLong(kvs[1].split(" ")[0]);
            break;
          case "Shared_Clean":
            sharedClean = Long.parseLong(kvs[1].split(" ")[0]);
            break;
          case "Shared_Dirty":
            sharedDirty = Long.parseLong(kvs[1].split(" ")[0]);
            break;
          case "Private_Clean":
            privateClean = Long.parseLong(kvs[1].split(" ")[0]);
            break;
          case "Private_Dirty":
            privateDirty = Long.parseLong(kvs[1].split(" ")[0]);
            break;
          case "Referenced":
            referenced = Long.parseLong(kvs[1].split(" ")[0]);
            break;
          case "Anonymous":
            anonymous = Long.parseLong(kvs[1].split(" ")[0]);
            break;
          case "LazyFree":
            lazyFree = Long.parseLong(kvs[1].split(" ")[0]);
            break;
          case "AnonHugePages":
            anonHugePages = Long.parseLong(kvs[1].split(" ")[0]);
            break;
          case "ShmemPmdMapped":
            shmemPmdMapped = Long.parseLong(kvs[1].split(" ")[0]);
            break;
          case "FilePmdMapped":
            filePmdMapped = Long.parseLong(kvs[1].split(" ")[0]);
            break;
          case "Shared_Hugetlb":
            sharedHugetlb = Long.parseLong(kvs[1].split(" ")[0]);
            break;
          case "Private_Hugetlb":
            privateHugetlb = Long.parseLong(kvs[1].split(" ")[0]);
            break;
          case "Swap":
            swap = Long.parseLong(kvs[1].split(" ")[0]);
            break;
          case "SwapPss":
            swapPss = Long.parseLong(kvs[1].split(" ")[0]);
            break;
          case "Locked":
            locked = Long.parseLong(kvs[1].split(" ")[0]);
            break;
        }
      }
      new SmapEntryEvent(
              startAddress,
              endAddress,
              perms,
              offset,
              major,
              inode,
              pathname,
              size,
              kernelPageSize,
              mmuPageSize,
              rss,
              pss,
              sharedClean,
              sharedDirty,
              privateClean,
              privateDirty,
              referenced,
              anonymous,
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
              vmFlags)
          .commit();
    } while (l != null);
  }
}
