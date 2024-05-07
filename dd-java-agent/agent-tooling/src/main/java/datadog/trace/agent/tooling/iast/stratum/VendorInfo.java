package datadog.trace.agent.tooling.iast.stratum;

/**
 * The vendorInfo describes the vendor-specific information
 * https://jakarta.ee/specifications/debugging/2.0/jdsol-spec-2.0#vendorsection
 */
public class VendorInfo implements Cloneable {
  private final String vendorId;

  private final String[] data;

  public VendorInfo(final String vendorId, final String[] data) {
    this.vendorId = vendorId;
    this.data = data;
  }

  @Override
  public Object clone() {
    return new VendorInfo(vendorId, data.clone());
  }

  public String getVendorId() {
    return vendorId;
  }

  public String[] getData() {
    return data;
  }
}
