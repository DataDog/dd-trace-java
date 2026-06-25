package datadog.crashtracking.dto;

import com.squareup.moshi.Json;
import java.util.Objects;

/**
 * JDK runtime information extracted from the hs_err crash log header and vm_info line. This
 * captures the exact JDK vendor and build so crash reports can be correlated with the specific
 * binaries in use.
 */
public final class RuntimeInfo {
  @Json(name = "jre_version")
  public final String jreVersion;

  @Json(name = "java_vm")
  public final String javaVm;

  @Json(name = "vm_info")
  public final String vmInfo;

  public RuntimeInfo(String jreVersion, String javaVm, String vmInfo) {
    this.jreVersion = jreVersion;
    this.javaVm = javaVm;
    this.vmInfo = vmInfo;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    RuntimeInfo that = (RuntimeInfo) o;
    return Objects.equals(jreVersion, that.jreVersion)
        && Objects.equals(javaVm, that.javaVm)
        && Objects.equals(vmInfo, that.vmInfo);
  }

  @Override
  public int hashCode() {
    return Objects.hash(jreVersion, javaVm, vmInfo);
  }
}
