package datadog.trace.api.civisibility;

import javax.annotation.Nullable;

// FIXME add Javadoc
//  (mention that endTime is in MICROSECONDS!)
//  (mention that start/end should be in the same thread)
//  (mention that setErrorInfo/setSkipReason need to be called before end())
public interface DDTest {

  void setTag(String key, Object value);

  void setErrorInfo(Throwable error);

  void setSkipReason(String skipReason);

  // FIXME either it should not return status at all, or should use an enum
  String end(@Nullable Long endTime);
}
