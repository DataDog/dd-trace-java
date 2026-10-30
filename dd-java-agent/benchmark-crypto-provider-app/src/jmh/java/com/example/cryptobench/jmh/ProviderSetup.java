package com.example.cryptobench.jmh;

import com.example.cryptobench.shared.Accp;
import java.security.Signature;

final class ProviderSetup {
  private ProviderSetup() {}

  static void apply(String provider) throws Exception {
    switch (provider) {
      case "jdk":
        break;
      case "accp":
        Accp.install();
        break;
      default:
        throw new IllegalArgumentException("provider must be jdk or accp: " + provider);
    }
    String resolved = Signature.getInstance("SHA256withRSA").getProvider().getName();
    boolean isAccp = Accp.NAME.equals(resolved);
    if (isAccp != "accp".equals(provider)) {
      throw new IllegalStateException("provider=" + provider + " but SHA256withRSA -> " + resolved);
    }
  }
}
