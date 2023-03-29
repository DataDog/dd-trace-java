package datadog.trace.api.civisibility;

import javax.annotation.Nullable;

// FIXME add Javadoc
//  (mention that startTime, endTime are in MICROSECONDS!)
//  (mention that start/end should be in the same thread)
//  (mention that setErrorInfo/setSkipReason need to be called before end())
public interface DDTestSuite {

  void setTag(String key, Object value);

  void setErrorInfo(Throwable error);

  void setSkipReason(String skipReason);

  void end(@Nullable Long endTime);

  DDTest testStart(String testName, @Nullable Long startTime);
}
