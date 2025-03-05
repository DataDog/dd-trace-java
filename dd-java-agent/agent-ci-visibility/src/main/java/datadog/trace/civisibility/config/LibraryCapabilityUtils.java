package datadog.trace.civisibility.config;

import datadog.trace.api.civisibility.config.LibraryCapability;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

public abstract class LibraryCapabilityUtils {

  public static final Map<LibraryCapability, String> CAPABILITY_TAG_MAP = capabilitiesTagMap();

  private static Map<LibraryCapability, String> capabilitiesTagMap() {
    Map<LibraryCapability, String> capabilitiesTags = new EnumMap<>(LibraryCapability.class);
    capabilitiesTags.put(LibraryCapability.TIA, LibraryCapability.TIA.asTag());
    capabilitiesTags.put(LibraryCapability.EFD, LibraryCapability.EFD.asTag());
    capabilitiesTags.put(LibraryCapability.ATR, LibraryCapability.ATR.asTag());
    capabilitiesTags.put(LibraryCapability.IMPACTED, LibraryCapability.IMPACTED.asTag());
    capabilitiesTags.put(LibraryCapability.FAIL_FAST, LibraryCapability.FAIL_FAST.asTag());
    capabilitiesTags.put(LibraryCapability.QUARANTINE, LibraryCapability.QUARANTINE.asTag());
    capabilitiesTags.put(LibraryCapability.DISABLED, LibraryCapability.DISABLED.asTag());
    capabilitiesTags.put(
        LibraryCapability.ATTEMPT_TO_FIX, LibraryCapability.ATTEMPT_TO_FIX.asTag());
    return capabilitiesTags;
  }

  public static Map<LibraryCapability, Boolean> filterCapabilities(
      Collection<LibraryCapability> available, Map<LibraryCapability, Boolean> capabilitiesStatus) {
    Map<LibraryCapability, Boolean> capabilities = new HashMap<>(capabilitiesStatus);
    capabilities.keySet().retainAll(available);
    return capabilities;
  }
}
