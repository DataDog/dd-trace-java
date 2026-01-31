package datadog.crashtracking.dto;

import com.squareup.moshi.Json;
import java.util.Objects;

public class SigInfo {
  @Json(name = "si_signo")
  public final Integer number;

  @Json(name = "si_code")
  public final Integer code;

  @Json(name = "si_signo_human_readable")
  public final String name;

  @Json(name = "si_code_human_readable")
  public final String action;

  @Json(name = "si_addr")
  public final String address;

  public SigInfo(Integer number, String name, Integer code, String action, String address) {
    this.number = number;
    this.name = name;
    this.address = address;
    this.code = code;
    this.action = action;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof SigInfo)) return false;
    SigInfo sigInfo = (SigInfo) o;
    return Objects.equals(number, sigInfo.number)
        && Objects.equals(name, sigInfo.name)
        && Objects.equals(address, sigInfo.address)
        && Objects.equals(code, sigInfo.code)
        && Objects.equals(action, sigInfo.action);
  }

  @Override
  public int hashCode() {
    return Objects.hash(number, name, address, code, action);
  }
}
