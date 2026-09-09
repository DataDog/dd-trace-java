package datadog.trace.bootstrap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AgentBootstrapAbortOnJdkToolTest {
  private String savedModuleMain;
  private String savedJavaCommand;

  @BeforeEach
  void saveAndClearProperties() {
    savedModuleMain = System.clearProperty("jdk.module.main");
    savedJavaCommand = System.clearProperty("sun.java.command");
  }

  @AfterEach
  void restoreProperties() {
    restoreProperty("jdk.module.main", savedModuleMain);
    restoreProperty("sun.java.command", savedJavaCommand);
  }

  private static void restoreProperty(String key, String previousValue) {
    if (previousValue == null) {
      System.clearProperty(key);
    } else {
      System.setProperty(key, previousValue);
    }
  }

  @Test
  void notAJdkToolWhenNoPropertiesSet() {
    assertFalse(AgentBootstrap.isJdkTool());
  }

  @Test
  void notAJdkToolWhenCommandIsNotAKnownTool() {
    System.setProperty("sun.java.command", "com.example.MyApplication");
    assertFalse(AgentBootstrap.isJdkTool());
  }

  @Test
  void notAJdkToolWhenModuleMainIsNotAKnownTool() {
    System.setProperty("jdk.module.main", "com.example.myapp");
    assertFalse(AgentBootstrap.isJdkTool());
  }

  @ParameterizedTest
  @ValueSource(strings = {
      // Standard JDK 9+ module-based tools
      // keytool
      "java.base",
      // javac
      "jdk.compiler",
      // jar
      "jdk.jartool",
      // javadoc
      "jdk.javadoc",
      // jcmd
      "jdk.jcmd",
      // jconsole
      "jdk.jconsole",
      // jshell
      "jdk.jshell",
      // jfr (JDK 9+)
      "jdk.jfr",
      // OpenJ9 / Semeru 11+ module-based tools
      // jextract, jpackcore
      "openj9.dtfj",
      // jdmpview
      "openj9.dtfjview",
      // traceformat
      "openj9.traceformat"
  })
  void isJdkToolByModuleMain(String moduleMain) {
    System.setProperty("jdk.module.main", moduleMain);
    assertTrue(AgentBootstrap.isJdkTool());
  }

  @ParameterizedTest
  @ValueSource(strings = {
      // IBM J9 JDK 8 specific tool main classes
      // keytool
      "com.ibm.crypto.tools.KeyTool",
      // kinit
      "com.ibm.security.krb5.internal.tools.Kinit",
      // klist
      "com.ibm.security.krb5.internal.tools.Klist",
      // ktab
      "com.ibm.security.krb5.internal.tools.Ktab",
      // jdmpview
      "com.ibm.jvm.dtfjview.DTFJView",
      // jextract
      "com.ibm.jvm.j9.dump.extract.Main",
      // ikeyman
      "com.ibm.gsk.ikeyman.Ikeyman",
      // ikeycmd
      "com.ibm.gsk.ikeyman.ikeycmd",
      // tnameserv
      "com.ibm.CosNaming.TransientNameServer",
      // idlj
      "com.ibm.idl.toJavaPortable.Compile",
      // OpenJ9 / Semeru 8 specific tool main classes (OpenJ9 reimplementation of HotSpot tools)
      // jcmd
      "openj9.tools.attach.diagnostics.tools.Jcmd",
      // jps
      "openj9.tools.attach.diagnostics.tools.Jps",
      // jstat
      "openj9.tools.attach.diagnostics.tools.Jstat",
      // jmap
      "openj9.tools.attach.diagnostics.tools.Jmap",
      // jstack
      "openj9.tools.attach.diagnostics.tools.Jstack",
      // traceformat
      "com.ibm.jvm.TraceFormat",
      // Standard JDK 8 tool main classes (Corretto 8 / OpenJDK 8)
      // jar
      "sun.tools.jar.Main",
      // javac
      "com.sun.tools.javac.Main",
      // javadoc
      "com.sun.tools.javadoc.Main",
      // javap
      "com.sun.tools.javap.Main",
      // javah
      "com.sun.tools.javah.Main",
      // keytool
      "sun.security.tools.keytool.Main",
      // jarsigner
      "sun.security.tools.jarsigner.Main",
      // policytool
      "sun.security.tools.policytool.PolicyTool",
      // jdb
      "com.sun.tools.example.debug.tty.TTY",
      // jdeps
      "com.sun.tools.jdeps.Main",
      // rmic
      "sun.rmi.rmic.Main",
      // rmiregistry
      "sun.rmi.registry.RegistryImpl",
      // rmid
      "sun.rmi.server.Activation",
      // extcheck
      "com.sun.tools.extcheck.Main",
      // serialver
      "sun.tools.serialver.SerialVer",
      // native2ascii
      "sun.tools.native2ascii.Main",
      // wsgen
      "com.sun.tools.internal.ws.WsGen",
      // wsimport
      "com.sun.tools.internal.ws.WsImport",
      // xjc
      "com.sun.tools.internal.xjc.Driver",
      // schemagen
      "com.sun.tools.internal.jxc.SchemaGenerator",
      // jrunscript
      "com.sun.tools.script.shell.Main",
      // jconsole
      "sun.tools.jconsole.JConsole",
      // appletviewer
      "sun.applet.Main",
      // tnameserv
      "com.sun.corba.se.impl.naming.cosnaming.TransientNameServer",
      // idlj
      "com.sun.tools.corba.se.idl.toJavaPortable.Compile",
      // orbd
      "com.sun.corba.se.impl.activation.ORBD",
      // servertool
      "com.sun.corba.se.impl.activation.ServerTool",
      // jps
      "sun.tools.jps.Jps",
      // jstack
      "sun.tools.jstack.JStack",
      // jmap
      "sun.tools.jmap.JMap",
      // jinfo
      "sun.tools.jinfo.JInfo",
      // jhat
      "com.sun.tools.hat.Main",
      // jstat
      "sun.tools.jstat.Jstat",
      // jstatd
      "sun.tools.jstatd.Jstatd",
      // jcmd
      "sun.tools.jcmd.JCmd",
      // jfr (OpenJDK 8u262+ backport)
      "jdk.jfr.internal.tool.Main",
      // jsadebugd
      "sun.jvm.hotspot.jdi.SADebugServer",
      // jjs (Nashorn JS shell, JDK 8)
      "jdk.nashorn.tools.Shell",
      // hsdb (HotSpot SA GUI debugger, JDK 8)
      "sun.jvm.hotspot.HSDB",
      // clhsdb (HotSpot SA command-line debugger, JDK 8)
      "sun.jvm.hotspot.CLHSDB"
  })
  void isJdkToolByCommand(String mainClass) {
    System.setProperty("sun.java.command", mainClass);
    assertTrue(AgentBootstrap.isJdkTool());
  }

  @Test
  void isJdkToolWhenCommandIncludesArguments() {
    System.setProperty("sun.java.command", "com.ibm.crypto.tools.KeyTool -list -v");
    assertTrue(AgentBootstrap.isJdkTool());
  }
}
