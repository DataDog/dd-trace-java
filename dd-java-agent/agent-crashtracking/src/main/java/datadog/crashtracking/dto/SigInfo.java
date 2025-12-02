package datadog.crashtracking.dto;

import java.util.Objects;

public class SigInfo {
  public final int number;
  public final String name;
  public final String address;

  public SigInfo(int number, String name, String address) {
    this.number = number;
    this.name = name;
    this.address = address;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof SigInfo)) return false;
    SigInfo sigInfo = (SigInfo) o;
    return number == sigInfo.number
        && Objects.equals(name, sigInfo.name)
        && Objects.equals(address, sigInfo.address);
  }

  @Override
  public int hashCode() {
    return Objects.hash(number, name, address);
  }
}
