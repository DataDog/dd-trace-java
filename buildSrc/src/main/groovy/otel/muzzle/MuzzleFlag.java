package otel.muzzle;

/**
 * This class is a helper class to convert OTel io.opentelemetry.javaagent.tooling.muzzle.references.Flag into
 * datadog.trace.agent.tooling.muzzle.Reference flags
 */
public class MuzzleFlag {
  /**
   * Convert an OTel field instruction node to get a flag into the related Datadog reference/ flag.
   * Conversion is based on the OTel enumerate name.
   *
   * @param flagType The type of flag to parse.
   * @param flagName The name of the flag to parse.
   * @return The Datadog reference flag, {@code -1} if no matching flag found.
   */
  public static int convertOtelFlag(String flagType, String flagName) {
    switch (flagType) {
      case "VisibilityFlag":
        switch (flagName) {
          case "PUBLIC":
            return 1; // EXPECTS_PUBLIC
          case "PROTECTED":
            return 2; // EXPECTS_PUBLIC_OR_PROTECTED
          case "PACKAGE":
            return 4; // EXPECTS_NON_PRIVATE
          case "PRIVATE":
            return 0; // No flag requirement
        }
        break;
      case "MinimumVisibilityFlag":
        switch (flagName) {
          case "PUBLIC":
            return 1; // EXPECTS_PUBLIC
          case "PROTECTED_OR_HIGHER":
            return 2; // EXPECTS_PUBLIC_OR_PROTECTED
          case "PACKAGE_OR_HIGHER":
            return 4; // EXPECTS_NON_PRIVATE
          case "PRIVATE_OR_HIGHER":
            return 0; // No flag requirement
        }
        break;
      case "ManifestationFlag":
        switch (flagName) {
          case "FINAL":
            return 0; // No flag requirement
          case "NON_FINAL":
            return 128; // EXPECTS_NON_FINAL
          case "ABSTRACT":
            return 0; // No flag requirement
          case "INTERFACE":
            return 32; // EXPECTS_INTERFACE
          case "NON_INTERFACE":
            return 64; // EXPECTS_NON_INTERFACE
        }
        break;
      case "OwnershipFlag":
        switch (flagName) {
          case "STATIC":
            return 8; // EXPECTS_STATIC
          case "NON_STATIC":
            return 0; // No flag requirement
        }
        break;
    }
    // No flag found
    return -1;
  }
}
