package com.datadog.iast.model.json;

import com.datadog.iast.model.Evidence;
import com.datadog.iast.model.Location;
import com.datadog.iast.model.Vulnerability;
import com.datadog.iast.model.VulnerabilityType;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonWriter;
import com.squareup.moshi.Moshi;
import java.io.IOException;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

class TruncatedVulnerabilitiesAdapter extends FormattingAdapter<TruncatedVulnerabilities> {

  private static final String MAX_SIZE_EXCEEDED = "MAX_SIZE_EXCEEDED";

  private final JsonAdapter<Vulnerability> vulnerabilityAdapter;

  public TruncatedVulnerabilitiesAdapter(Moshi moshi) {
    this.vulnerabilityAdapter = new TruncatedVulnerabilityAdapter(moshi);
  }

  @Override
  public void toJson(@Nonnull JsonWriter writer, @Nullable TruncatedVulnerabilities value)
      throws IOException {
    if (value == null) {
      writer.nullValue();
      return;
    }
    final List<Vulnerability> vulnerabilities = value.getVulnerabilities();
    writer.beginObject();
    if (vulnerabilities != null && !vulnerabilities.isEmpty()) {
      writer.name("vulnerabilities");
      writer.beginArray();
      for (Vulnerability vulnerability : vulnerabilities) {
        vulnerabilityAdapter.toJson(writer, vulnerability);
      }
      writer.endArray();
    }
    writer.endObject();
  }

  private static class TruncatedVulnerabilityAdapter extends FormattingAdapter<Vulnerability> {

    private final JsonAdapter<VulnerabilityType> vulnerabilityTypeAdapter;

    private final JsonAdapter<Evidence> evidenceAdapter;

    private final JsonAdapter<Location> locationAdapter;

    public TruncatedVulnerabilityAdapter(Moshi moshi) {
      this.vulnerabilityTypeAdapter = moshi.adapter(VulnerabilityType.class);
      this.evidenceAdapter = new TruncatedEvidenceAdapter();
      this.locationAdapter = moshi.adapter(Location.class);
    }

    @Override
    public void toJson(@Nonnull JsonWriter writer, @Nullable Vulnerability value)
        throws IOException {
      if (value == null) {
        return;
      }
      writer.beginObject();
      writer.name("type");
      vulnerabilityTypeAdapter.toJson(writer, value.getType());
      writer.name("evidence");
      evidenceAdapter.toJson(writer, value.getEvidence());
      writer.name("hash");
      writer.value(value.getHash());
      writer.name("location");
      locationAdapter.toJson(writer, value.getLocation());
      writer.endObject();
    }
  }

  private static class TruncatedEvidenceAdapter extends FormattingAdapter<Evidence> {
    @Override
    public void toJson(@Nonnull JsonWriter writer, @Nullable Evidence evidence) throws IOException {
      writer.beginObject();
      writer.name("value");
      writer.value(MAX_SIZE_EXCEEDED);
      writer.endObject();
    }
  }
}
