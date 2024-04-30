package datadog.trace.agent.tooling.iast.stratum;

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
