package com.example.cryptobench;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.security.MessageDigest;
import java.security.Provider;
import java.security.Security;
import java.security.Signature;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Process stats for the load generator and which provider serves each algorithm. */
@RestController
public class AdminController {
  private final CryptoController crypto;

  public AdminController(CryptoController crypto) {
    this.crypto = crypto;
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
    sb.append("cpuNanos=").append(cpu).append('\n');
    sb.append("gcCount=").append(gcCount).append('\n');
    sb.append("gcMs=").append(gcMs).append('\n');
    return sb.toString();
  }

  /** key=value lines: provider list head and the provider each benchmarked algorithm resolves to. */
  @GetMapping("/admin/providers")
  public String providers() throws Exception {
    Provider[] list = Security.getProviders();
    StringBuilder sb = new StringBuilder(512);
    sb.append("position1=").append(list[0].getName()).append('\n');
    sb.append("providerCount=").append(list.length).append('\n');
    sb.append("Signature.SHA256withRSA=")
        .append(Signature.getInstance("SHA256withRSA").getProvider().getName())
        .append('\n');
    sb.append("Cipher.AES/GCM/NoPadding=")
        .append(Cipher.getInstance("AES/GCM/NoPadding").getProvider().getName())
        .append('\n');
    sb.append("Mac.HmacSHA256=").append(Mac.getInstance("HmacSHA256").getProvider().getName()).append('\n');
    sb.append("MessageDigest.SHA-256=")
        .append(MessageDigest.getInstance("SHA-256").getProvider().getName())
        .append('\n');
    sb.append("KeyAgreement.ECDH=")
        .append(KeyAgreement.getInstance("ECDH").getProvider().getName())
        .append('\n');
    sb.append("KeyAgreement.XDH=")
        .append(KeyAgreement.getInstance("XDH").getProvider().getName())
        .append('\n');
    sb.append("jwtKeyClass=").append(crypto.jwtKey.getClass().getName()).append('\n');
    return sb.toString();
  }
}
