package datadog.crashtracking.dto;

import com.squareup.moshi.Json;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import datadog.trace.util.RandomUtils;
import java.io.IOException;
import java.util.Objects;

public final class CrashLog {
  private static final int VERSION = 0;

  public static final JsonAdapter<CrashLog> ADAPTER;

  static {
    Moshi moshi = new Moshi.Builder().add(new DynamicLibs.JsonAdapter()).build();
    ADAPTER = moshi.adapter(CrashLog.class);
  }

  public final String uuid;

  @Json(name = "data_schema_version")
  public final String dataSchemaVersion;

  public final String timestamp;
  public final boolean incomplete;
  public final ErrorData error;
  public final Metadata metadata;

  @Json(name = "os_info")
  public final OSInfo osInfo;

  @Json(name = "proc_info")
  public final ProcInfo procInfo;

  @Json(name = "version_id")
  public final int version = VERSION;

  @Json(name = "sig_info")
  public final SigInfo sigInfo;

  public final Experimental experimental;

  /**
   * Useful files for triage and debugging (e.g. {@code /proc/self/maps}, {@code
   * dynamic_libraries}).
   */
  public final DynamicLibs files;

  public CrashLog(
      String uuid,
      boolean incomplete,
      String timestamp,
      ErrorData error,
      Metadata metadata,
      OSInfo osInfo,
      ProcInfo procInfo,
      SigInfo sigInfo,
      String dataSchemaVersion) {
    this(
        uuid,
        incomplete,
        timestamp,
        error,
        metadata,
        osInfo,
        procInfo,
        sigInfo,
        dataSchemaVersion,
        null,
        null);
  }

  public CrashLog(
      String uuid,
      boolean incomplete,
      String timestamp,
      ErrorData error,
      Metadata metadata,
      OSInfo osInfo,
      ProcInfo procInfo,
      SigInfo sigInfo,
      String dataSchemaVersion,
      Experimental experimental,
      DynamicLibs files) {
    this.uuid = uuid != null ? uuid : RandomUtils.randomUUID().toString();
    this.incomplete = incomplete;
    this.timestamp = timestamp;
    this.error = error;
    this.metadata = metadata;
    this.osInfo = osInfo;
    this.procInfo = procInfo;
    this.sigInfo = sigInfo;
    this.dataSchemaVersion = dataSchemaVersion;
    this.experimental = experimental;
    this.files = files;
  }

  public String toJson() {
    return ADAPTER.toJson(this);
  }

  public static CrashLog fromJson(String json) throws IOException {
    return ADAPTER.fromJson(json);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    CrashLog crashLog = (CrashLog) o;
    return incomplete == crashLog.incomplete
        && Objects.equals(uuid, crashLog.uuid)
        && Objects.equals(timestamp, crashLog.timestamp)
        && Objects.equals(error, crashLog.error)
        && Objects.equals(metadata, crashLog.metadata)
        && Objects.equals(osInfo, crashLog.osInfo)
        && Objects.equals(procInfo, crashLog.procInfo)
        && Objects.equals(sigInfo, crashLog.sigInfo)
        && Objects.equals(dataSchemaVersion, crashLog.dataSchemaVersion)
        && Objects.equals(experimental, crashLog.experimental)
        && Objects.equals(files, crashLog.files);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        uuid,
        timestamp,
        incomplete,
        error,
        metadata,
        osInfo,
        procInfo,
        sigInfo,
        version,
        dataSchemaVersion,
        experimental,
        files);
  }

  public boolean equalsForTest(Object o) {
    // for tests, we need to ignore OSInfo and Metadata part
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    CrashLog crashLog = (CrashLog) o;
    return incomplete == crashLog.incomplete
        && version == crashLog.version
        && Objects.equals(uuid, crashLog.uuid)
        && Objects.equals(timestamp, crashLog.timestamp)
        && Objects.equals(error, crashLog.error)
        && Objects.equals(procInfo, crashLog.procInfo)
        && Objects.equals(sigInfo, crashLog.sigInfo)
        && Objects.equals(dataSchemaVersion, crashLog.dataSchemaVersion)
        && Objects.equals(experimental, crashLog.experimental)
        && Objects.equals(files, crashLog.files);
  }
}
