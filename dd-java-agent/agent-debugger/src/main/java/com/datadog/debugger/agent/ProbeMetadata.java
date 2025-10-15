package com.datadog.debugger.agent;

import datadog.trace.bootstrap.debugger.ProbeImplementation;
import java.util.concurrent.atomic.AtomicReferenceArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ProbeMetadata {
  private static final Logger LOGGER = LoggerFactory.getLogger(ProbeMetadata.class);

  private int size;
  private Object lock = new Object();
  private volatile AtomicReferenceArray<ProbeImplementation> probeImplementations =
      new AtomicReferenceArray<>(64);

  public int addProbe(ProbeImplementation probeImplementation) {
    synchronized (lock) {
      int len = probeImplementations.length();
      if (size >= len) {
        AtomicReferenceArray<ProbeImplementation> newArray = new AtomicReferenceArray<>(len * 2);
        for (int i = 0; i < len; i++) {
          newArray.set(i, probeImplementations.get(i));
        }
        probeImplementations = newArray;
      }
      int idx = 0;
      while (probeImplementations.get(idx) != null) {
        idx++;
      }
      probeImplementations.set(idx, probeImplementation);
      LOGGER.debug(
          "Assigned probeId={} to ProbeMetadata Idx={}",
          probeImplementation.getProbeId().getEncodedId(),
          idx);
      size++;
      return idx;
    }
  }

  public void removeProbe(int idx) {
    synchronized (lock) {
      probeImplementations.set(idx, null);
      size--;
    }
  }

  public void removeProbe(String encodedProbeId) {
    synchronized (lock) {
      for (int i = 0; i < probeImplementations.length(); i++) {
        ProbeImplementation probeImplementation = probeImplementations.get(i);
        if (probeImplementation != null
            && probeImplementation.getProbeId().getEncodedId().equals(encodedProbeId)) {
          probeImplementations.set(i, null);
          size--;
          return;
        }
      }
    }
  }

  public ProbeImplementation getProbe(int idx) {
    return probeImplementations.get(idx);
  }

  public int size() {
    synchronized (lock) {
      return size;
    }
  }
}
