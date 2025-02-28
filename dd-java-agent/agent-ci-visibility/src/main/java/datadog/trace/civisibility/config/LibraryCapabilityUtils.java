package datadog.trace.civisibility.config;

import datadog.trace.api.DDTags;
import datadog.trace.api.civisibility.config.LibraryCapability;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

public abstract class LibraryCapabilityUtils {

  public static final Map<LibraryCapability, String> CAPABILITY_TAG_MAP = capabilitiesTagMap();

  private static Map<LibraryCapability, String> capabilitiesTagMap() {
    Map<LibraryCapability, String> capabilitiesTags = new EnumMap<>(LibraryCapability.class);
    capabilitiesTags.put(LibraryCapability.TIA, DDTags.LIBRARY_CAPABILITIES_TIA);
    capabilitiesTags.put(LibraryCapability.EFD, DDTags.LIBRARY_CAPABILITIES_EFD);
    capabilitiesTags.put(LibraryCapability.ATR, DDTags.LIBRARY_CAPABILITIES_ATR);
    capabilitiesTags.put(LibraryCapability.IMPACTED, DDTags.LIBRARY_CAPABILITIES_IMPACTED_TESTS);
    capabilitiesTags.put(
        LibraryCapability.FAIL_FAST, DDTags.LIBRARY_CAPABILITIES_FAIL_FAST_TEST_ORDER);
    capabilitiesTags.put(LibraryCapability.QUARANTINE, DDTags.LIBRARY_CAPABILITIES_QUARANTINE);
    capabilitiesTags.put(LibraryCapability.DISABLED, DDTags.LIBRARY_CAPABILITIES_DISABLED);
    capabilitiesTags.put(
        LibraryCapability.ATTEMPT_TO_FIX, DDTags.LIBRARY_CAPABILITIES_ATTEMPT_TO_FIX);
    return capabilitiesTags;
  }

  public static Map<LibraryCapability, Boolean> filterCapabilities(
      Collection<LibraryCapability> available, Map<LibraryCapability, Boolean> capabilitiesStatus) {
    Map<LibraryCapability, Boolean> capabilities = new HashMap<>(capabilitiesStatus);
    capabilities.keySet().retainAll(available);
    return capabilities;
  }
}
