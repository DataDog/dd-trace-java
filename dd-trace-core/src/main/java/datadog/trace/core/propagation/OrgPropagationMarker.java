package datadog.trace.core.propagation;

/**
 * Holds the local Org Propagation Marker (OPM) for the current service, received from the Datadog
 * agent /info endpoint. The OPM is used to detect cross-org trace stitching and protect against
 * adoption of foreign sampling decisions.
 */
public final class OrgPropagationMarker {
  private static volatile String localOpm;

  private OrgPropagationMarker() {}

  public static void setLocalOpm(String opm) {
    localOpm = opm;
  }

  public static String getLocalOpm() {
    return localOpm;
  }
}
