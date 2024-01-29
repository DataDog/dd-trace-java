package com.datadog.iast.sensitive;

import com.datadog.iast.model.Evidence;
import com.datadog.iast.util.Ranged;
import java.util.NoSuchElementException;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

public class HeaderRegexpTokenizer implements SensitiveHandler.Tokenizer {

  @Nullable private Ranged current;

  private boolean checked = false;

  private String evidenceValue;

  private Pattern namePattern;

  private Pattern valuePattern;

  public HeaderRegexpTokenizer(final Evidence evidence, Pattern namePattern, Pattern valuePattern) {
    this.evidenceValue = evidence.getValue();
    this.namePattern = namePattern;
    this.valuePattern = valuePattern;
  }

  @Override
  public boolean next() {
    current = buildNext();
    return current != null;
  }

  @Override
  public Ranged current() {
    if (current == null) {
      throw new NoSuchElementException();
    }
    return current;
  }

  @Nullable
  private Ranged buildNext() {
    if (!checked) {
      checked = true;
      // Header evidence format is <headerName>: <headerValue>
      int index = evidenceValue.indexOf(":");
      String name = evidenceValue.substring(0, index);
      String value = evidenceValue.substring(index + 1);
      if (namePattern.matcher(name).find() || valuePattern.matcher(value).find()) {
        return Ranged.build(name.length() + 2, value.length());
      }
    }
    return null;
  }
}
