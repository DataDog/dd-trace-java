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
  @ValueSource(
      strings = {
        "java.base", // keytool
        "jdk.compiler", // javac
        "jdk.jartool", // jar
        "jdk.javadoc", // javadoc
        "jdk.jcmd", // jcmd
        "jdk.jconsole", // jconsole
        "jdk.jshell", // jshell
        "jdk.jfr", // jfr (JDK 9+)
      })
  void isJdkToolByModuleMain(String moduleMain) {
    System.setProperty("jdk.module.main", moduleMain);
    assertTrue(AgentBootstrap.isJdkTool());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        // IBM J9 JDK 8 specific tool main classes
        "com.ibm.crypto.tools.KeyTool", // keytool
        "com.ibm.security.krb5.internal.tools.Kinit", // kinit
        "com.ibm.security.krb5.internal.tools.Klist", // klist
        "com.ibm.security.krb5.internal.tools.Ktab", // ktab
        "com.ibm.jvm.dtfjview.DTFJView", // jdmpview
        "com.ibm.gsk.ikeyman.ikeycmd", // ikeycmd
        "com.ibm.CosNaming.TransientNameServer", // tnameserv
        "com.ibm.idl.toJavaPortable.Compile", // idlj
        // Standard JDK 8 tool main classes (Corretto 8 / OpenJDK 8)
        "sun.tools.jar.Main", // jar
        "com.sun.tools.javac.Main", // javac
        "com.sun.tools.javadoc.Main", // javadoc
        "com.sun.tools.javap.Main", // javap
        "com.sun.tools.javah.Main", // javah
        "sun.security.tools.keytool.Main", // keytool
        "sun.security.tools.jarsigner.Main", // jarsigner
        "sun.security.tools.policytool.PolicyTool", // policytool
        "com.sun.tools.example.debug.tty.TTY", // jdb
        "com.sun.tools.jdeps.Main", // jdeps
        "sun.rmi.rmic.Main", // rmic
        "sun.rmi.registry.RegistryImpl", // rmiregistry
        "sun.rmi.server.Activation", // rmid
        "com.sun.tools.extcheck.Main", // extcheck
        "sun.tools.serialver.SerialVer", // serialver
        "sun.tools.native2ascii.Main", // native2ascii
        "com.sun.tools.internal.ws.WsGen", // wsgen
        "com.sun.tools.internal.ws.WsImport", // wsimport
        "com.sun.tools.internal.xjc.Driver", // xjc
        "com.sun.tools.internal.jxc.SchemaGenerator", // schemagen
        "com.sun.tools.script.shell.Main", // jrunscript
        "sun.tools.jconsole.JConsole", // jconsole
        "sun.applet.Main", // appletviewer
        "com.sun.corba.se.impl.naming.cosnaming.TransientNameServer", // tnameserv
        "com.sun.tools.corba.se.idl.toJavaPortable.Compile", // idlj
        "com.sun.corba.se.impl.activation.ORBD", // orbd
        "com.sun.corba.se.impl.activation.ServerTool", // servertool
        "sun.tools.jps.Jps", // jps
        "sun.tools.jstack.JStack", // jstack
        "sun.tools.jmap.JMap", // jmap
        "sun.tools.jinfo.JInfo", // jinfo
        "com.sun.tools.hat.Main", // jhat
        "sun.tools.jstat.Jstat", // jstat
        "sun.tools.jstatd.Jstatd", // jstatd
        "sun.tools.jcmd.JCmd", // jcmd
        "jdk.jfr.internal.tool.Main", // jfr (OpenJDK 8u262+ backport)
        "sun.jvm.hotspot.jdi.SADebugServer", // jsadebugd
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
