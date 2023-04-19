package datadog.trace.instrumentation.testng;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.testng.IClass;
import org.testng.IRetryAnalyzer;
import org.testng.ITestClass;
import org.testng.ITestNGMethod;
import org.testng.internal.ConstructorOrMethod;
import org.testng.xml.XmlTest;

/**
 * A stub implementation of ITestNGMethod interface, needed to pass ITestClass into
 * org.testng.ClassMethodMap#removeAndCheckIfLast(org.testng.ITestNGMethod, java.lang.Object) in
 * order to get a response about its internal state
 */
public class TestNGMethod implements ITestNGMethod {

  private final ITestClass testClass;

  public TestNGMethod(ITestClass testClass) {
    this.testClass = testClass;
  }

  @Override
  public ITestClass getTestClass() {
    return testClass;
  }

  @Override
  public Class getRealClass() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setTestClass(ITestClass iTestClass) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Method getMethod() {
    throw new UnsupportedOperationException();
  }

  @Override
  public String getMethodName() {
    throw new UnsupportedOperationException();
  }

  @Override
  public Object[] getInstances() {
    throw new UnsupportedOperationException();
  }

  @Override
  public Object getInstance() {
    throw new UnsupportedOperationException();
  }

  @Override
  public long[] getInstanceHashCodes() {
    throw new UnsupportedOperationException();
  }

  @Override
  public String[] getGroups() {
    throw new UnsupportedOperationException();
  }

  @Override
  public String[] getGroupsDependedUpon() {
    throw new UnsupportedOperationException();
  }

  @Override
  public String getMissingGroup() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setMissingGroup(String s) {
    throw new UnsupportedOperationException();
  }

  @Override
  public String[] getBeforeGroups() {
    throw new UnsupportedOperationException();
  }

  @Override
  public String[] getAfterGroups() {
    throw new UnsupportedOperationException();
  }

  @Override
  public String[] getMethodsDependedUpon() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void addMethodDependedUpon(String s) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean isTest() {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean isBeforeMethodConfiguration() {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean isAfterMethodConfiguration() {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean isBeforeClassConfiguration() {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean isAfterClassConfiguration() {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean isBeforeSuiteConfiguration() {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean isAfterSuiteConfiguration() {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean isBeforeTestConfiguration() {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean isAfterTestConfiguration() {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean isBeforeGroupsConfiguration() {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean isAfterGroupsConfiguration() {
    throw new UnsupportedOperationException();
  }

  @Override
  public long getTimeOut() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setTimeOut(long l) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int getInvocationCount() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setInvocationCount(int i) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int getSuccessPercentage() {
    throw new UnsupportedOperationException();
  }

  @Override
  public String getId() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setId(String s) {
    throw new UnsupportedOperationException();
  }

  @Override
  public long getDate() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setDate(long l) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean canRunFromClass(IClass iClass) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean isAlwaysRun() {
    throw new UnsupportedOperationException();
  }

  @Override
  public int getThreadPoolSize() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setThreadPoolSize(int i) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean getEnabled() {
    throw new UnsupportedOperationException();
  }

  @Override
  public String getDescription() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void incrementCurrentInvocationCount() {
    throw new UnsupportedOperationException();
  }

  @Override
  public int getCurrentInvocationCount() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setParameterInvocationCount(int i) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int getParameterInvocationCount() {
    throw new UnsupportedOperationException();
  }

  @Override
  public ITestNGMethod clone() {
    throw new UnsupportedOperationException();
  }

  @Override
  public IRetryAnalyzer getRetryAnalyzer() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setRetryAnalyzer(IRetryAnalyzer iRetryAnalyzer) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean skipFailedInvocations() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setSkipFailedInvocations(boolean b) {
    throw new UnsupportedOperationException();
  }

  @Override
  public long getInvocationTimeOut() {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean ignoreMissingDependencies() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setIgnoreMissingDependencies(boolean b) {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<Integer> getInvocationNumbers() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setInvocationNumbers(List<Integer> list) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void addFailedInvocationNumber(int i) {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<Integer> getFailedInvocationNumbers() {
    throw new UnsupportedOperationException();
  }

  @Override
  public int getPriority() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setPriority(int i) {
    throw new UnsupportedOperationException();
  }

  @Override
  public XmlTest getXmlTest() {
    throw new UnsupportedOperationException();
  }

  @Override
  public ConstructorOrMethod getConstructorOrMethod() {
    throw new UnsupportedOperationException();
  }

  @Override
  public Map<String, String> findMethodParameters(XmlTest xmlTest) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int compareTo(Object o) {
    throw new UnsupportedOperationException();
  }
}
