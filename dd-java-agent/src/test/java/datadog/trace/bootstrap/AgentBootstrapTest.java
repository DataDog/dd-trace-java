package datadog.trace.bootstrap;

import static datadog.trace.bootstrap.AgentBootstrap.exceptionCauseChainContains;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class AgentBootstrapTest {
  private static final String IO_EXCEPTION = "java.io.IOException";
  private static final String SSI_ATTACH_ARGS =
      "dd.injection.enabled=tracer,dd.injection.mode=attach,"
          + "dd.telemetry.forwarder.path=/opt/datadog-packages/datadog-apm-inject/stable/inject/process";
  private static final String NEW_RELIC_MARKER = "com.newrelic.bootstrap.BootstrapAgent";

  @AfterEach
  void clearSystemProperties() {
    System.clearProperty(AgentBootstrap.LIB_INJECTION_ENABLED_SYS_PROP);
    System.clearProperty(AgentBootstrap.LIB_INJECTION_FORCE_SYS_PROP);
    System.clearProperty(AgentBootstrap.LIB_INJECTION_MODE_SYS_PROP);
  }

  @Test
  void returnTrueWhenFirstExceptionInTheCauseChainIsTheSpecifiedException() {
    Exception ex = new IOException();

    assertTrue(exceptionCauseChainContains(ex, IO_EXCEPTION));
  }

  @Test
  void returnFalseWhenExceptionCauseChainDoesNotContainSpecifiedException() {
    Exception ex = new NullPointerException();

    assertFalse(exceptionCauseChainContains(ex, IO_EXCEPTION));
  }

  @Test
  void returnTrueWhenExceptionCauseChainContainsSpecifiedExceptionAsACause() {
    Exception ex = new Exception(new IOException());

    assertTrue(exceptionCauseChainContains(ex, IO_EXCEPTION));
  }

  @Test
  void returnFalseWhenExceptionCauseChainHasACycle() {
    Exception ex = mock(Exception.class);
    when(ex.getCause()).thenReturn(ex);

    assertFalse(exceptionCauseChainContains(ex, IO_EXCEPTION));
  }

  @TableTest({
    "scenario           | agentArgs                    | key               | expected",
    "null args          |                              | dd.injection.mode |         ",
    "empty args         | ''                           | dd.injection.mode |         ",
    "single entry       | 'dd.injection.mode=attach'   | dd.injection.mode | attach  ",
    "first entry        | 'a=1,b=2,c=3'                | a                 | 1       ",
    "middle entry       | 'a=1,b=2,c=3'                | b                 | 2       ",
    "last entry         | 'a=1,b=2,c=3'                | c                 | 3       ",
    "missing key        | 'a=1,b=2'                    | c                 |         ",
    "key prefix         | 'dd.injection.mode.x=attach' | dd.injection.mode |         ",
    "key suffix         | 'x.dd.injection.mode=attach' | dd.injection.mode |         ",
    "trimmed            | ' a = 1 , b = 2 '            | b                 | 2       ",
    "empty value        | 'a=,b=2'                     | a                 |         ",
    "no separator       | 'a,b=2'                      | a                 |         ",
    "separator in value | 'a=x=y,b=2'                  | a                 | x=y     ",
    "trailing comma     | 'a=1,'                       | a                 | 1       ",
    "only commas        | ',,,'                        | a                 |         ",
    "plain arg          | 'aot_training'               | dd.injection.mode |         "
  })
  void parseAgentArg(String agentArgs, String key, String expected) {
    assertEquals(expected, AgentBootstrap.getAgentArg(agentArgs, key));
  }

  @Test
  void parseSsiAttachAgentArgs() {
    assertEquals("tracer", AgentBootstrap.getAgentArg(SSI_ATTACH_ARGS, "dd.injection.enabled"));
    assertEquals("attach", AgentBootstrap.getAgentArg(SSI_ATTACH_ARGS, "dd.injection.mode"));
    assertEquals(
        "/opt/datadog-packages/datadog-apm-inject/stable/inject/process",
        AgentBootstrap.getTelemetryForwarderPath(SSI_ATTACH_ARGS));
  }

  @Test
  void injectionNotEnabledByDefault() {
    assertFalse(AgentBootstrap.isInjectionEnabled(null));
    assertFalse(AgentBootstrap.isInjectionEnabled("dd.service=foo"));
    assertFalse(AgentBootstrap.isAttachMode(null));
    assertFalse(AgentBootstrap.isInjectionForced(null));
  }

  @Test
  void injectionEnabledFromAgentArgs() {
    assertTrue(AgentBootstrap.isInjectionEnabled(SSI_ATTACH_ARGS));
    assertTrue(AgentBootstrap.isAttachMode(SSI_ATTACH_ARGS));
  }

  @Test
  void injectionEnabledFromSystemProperties() {
    System.setProperty(AgentBootstrap.LIB_INJECTION_ENABLED_SYS_PROP, "tracer");
    System.setProperty(AgentBootstrap.LIB_INJECTION_MODE_SYS_PROP, "attach");

    assertTrue(AgentBootstrap.isInjectionEnabled(null));
    assertTrue(AgentBootstrap.isAttachMode(null));
  }

  @TableTest({
    "scenario | agentArgs                                          | expected",
    "missing  | 'dd.injection.enabled=tracer'                      | false   ",
    "true     | 'dd.injection.enabled=tracer,dd.inject.force=true' | true    ",
    "TRUE     | 'dd.inject.force=TRUE'                             | true    ",
    "1        | 'dd.inject.force=1'                                | true    ",
    "false    | 'dd.inject.force=false'                            | false   "
  })
  void injectionForcedFromAgentArgs(String agentArgs, boolean expected) {
    assertEquals(expected, AgentBootstrap.isInjectionForced(agentArgs));
  }

  @Test
  void injectionForcedSystemPropertyTakesPrecedenceOverAgentArgs() {
    System.setProperty(AgentBootstrap.LIB_INJECTION_FORCE_SYS_PROP, "false");

    assertFalse(AgentBootstrap.isInjectionForced("dd.inject.force=true"));
  }

  @Test
  void notAttachModeForOtherModes() {
    assertFalse(AgentBootstrap.isAttachMode("dd.injection.mode=exec"));
  }

  @Test
  void doNotAbortWithoutInjection() {
    Instrumentation inst = instrumentationWithLoadedClasses(markerClass(NEW_RELIC_MARKER));

    assertFalse(
        AgentBootstrap.shouldAbortDueToOtherJavaAgents(
            "dd.injection.mode=attach", inst, Collections.<File>emptyList()));
  }

  @Test
  void abortWhenOtherApmAgentIsLoadedInAttachMode() {
    Instrumentation inst = instrumentationWithLoadedClasses(markerClass(NEW_RELIC_MARKER));

    assertTrue(
        AgentBootstrap.shouldAbortDueToOtherJavaAgents(
            SSI_ATTACH_ARGS, inst, Collections.<File>emptyList()));
  }

  @Test
  void doNotAbortWhenInjectionIsForced() {
    Instrumentation inst = instrumentationWithLoadedClasses(markerClass(NEW_RELIC_MARKER));

    assertFalse(
        AgentBootstrap.shouldAbortDueToOtherJavaAgents(
            SSI_ATTACH_ARGS + ",dd.inject.force=true", inst, Collections.<File>emptyList()));
  }

  @TableTest({
    "scenario            | agentArgs                                              | agentFile         | expected",
    "attach, other agent | 'dd.injection.enabled=tracer,dd.injection.mode=attach' | other.jar         | true    ",
    "attach, log4j patch | 'dd.injection.enabled=tracer,dd.injection.mode=attach' | log4j.jar         | false   ",
    "exec, single agent  | 'dd.injection.enabled=tracer'                          | dd-java-agent.jar | false   "
  })
  void abortOnOtherJavaAgentFileDependsOnInjectionMode(
      String agentArgs, String agentFile, boolean expected) {
    Instrumentation inst = instrumentationWithLoadedClasses(String.class);

    assertEquals(
        expected,
        AgentBootstrap.shouldAbortDueToOtherJavaAgents(
            agentArgs, inst, Collections.singletonList(new File(agentFile))));
  }

  @TableTest({
    "scenario                 | agentFiles                     | ownAgentFileCount | expected",
    "attach, no other agent   | []                             | 0                 | false   ",
    "attach, one other agent  | [other.jar]                    | 0                 | true    ",
    "attach, log4j patch only | [log4j-patch.jar]              | 0                 | false   ",
    "exec, only dd agent      | [dd-java-agent.jar]            | 1                 | false   ",
    "exec, one other agent    | [dd-java-agent.jar, other.jar] | 1                 | true    ",
    "exec, log4j patch        | [dd-java-agent.jar, log4j.jar] | 1                 | false   ",
    "exec, two other agents   | [dd.jar, log4j.jar, other.jar] | 1                 | true    "
  })
  void detectOtherJavaAgentFiles(List<String> agentFiles, int ownAgentFileCount, boolean expected) {
    List<File> files = new ArrayList<>();
    for (String agentFile : agentFiles) {
      files.add(new File(agentFile));
    }

    assertEquals(expected, AgentBootstrap.hasOtherJavaAgentFiles(files, ownAgentFileCount));
  }

  @Test
  void findLoadedClassByName() {
    Class<?>[] loadedClasses = {String.class, AgentBootstrapTest.class};

    assertEquals(
        "java.lang.String",
        AgentBootstrap.findLoadedClass(loadedClasses, new String[] {"x.Y", "java.lang.String"}));
    assertNull(AgentBootstrap.findLoadedClass(loadedClasses, new String[] {"x.Y"}));
  }

  @Test
  void noOtherApmAgentLoaded() {
    Class<?>[] loadedClasses = {String.class, AgentBootstrapTest.class};

    assertNull(
        AgentBootstrap.findLoadedClass(
            loadedClasses, AgentBootstrap.OTHER_APM_AGENT_MARKER_CLASSES));
  }

  private static Instrumentation instrumentationWithLoadedClasses(Class<?>... loadedClasses) {
    Instrumentation inst = mock(Instrumentation.class);
    when(inst.getAllLoadedClasses()).thenReturn(loadedClasses);
    return inst;
  }

  private static Class<?> markerClass(String name) {
    try {
      return new MarkerClassLoader(name).loadClass(name);
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException(e);
    }
  }

  private static final class MarkerClassLoader extends ClassLoader {
    private static final int JAVA_8_CLASS_VERSION = 52;
    private static final int CONSTANT_UTF8 = 1;
    private static final int CONSTANT_CLASS = 7;
    private static final int ACC_PUBLIC_SUPER = 0x0021;

    private final String markerClass;

    MarkerClassLoader(String markerClass) {
      super(null);
      this.markerClass = markerClass;
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
      if (!name.equals(this.markerClass)) {
        throw new ClassNotFoundException(name);
      }
      byte[] bytes = emptyClass(name.replace('.', '/'));
      return defineClass(name, bytes, 0, bytes.length);
    }

    private static byte[] emptyClass(String internalName) {
      try {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(0xCAFEBABE);
        out.writeShort(0);
        out.writeShort(JAVA_8_CLASS_VERSION);
        out.writeShort(5);
        out.writeByte(CONSTANT_UTF8);
        out.writeUTF(internalName);
        out.writeByte(CONSTANT_CLASS);
        out.writeShort(1);
        out.writeByte(CONSTANT_UTF8);
        out.writeUTF("java/lang/Object");
        out.writeByte(CONSTANT_CLASS);
        out.writeShort(3);
        out.writeShort(ACC_PUBLIC_SUPER);
        out.writeShort(2);
        out.writeShort(4);
        out.writeShort(0);
        out.writeShort(0);
        out.writeShort(0);
        out.writeShort(0);
        return bytes.toByteArray();
      } catch (IOException e) {
        throw new IllegalStateException(e);
      }
    }
  }
}
