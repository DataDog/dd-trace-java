package datadog.telemetry.dependency;

import java.io.File;
import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.UndeclaredThrowableException;
import java.net.URI;
import java.net.URL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JbossVirtualFileHelper {

  private final MethodHandle getPhysicalFile;
  private final MethodHandle getName;

  private static JbossVirtualFileHelper jbossVirtualFileHelper;
  public static final JbossVirtualFileHelper FAILED_HELPER = new JbossVirtualFileHelper();

  private static final Logger log = LoggerFactory.getLogger(JbossVirtualFileHelper.class);

  public JbossVirtualFileHelper(ClassLoader loader) throws Exception {
    MethodHandles.Lookup lookup = MethodHandles.lookup();
    Class<?> virtualFileCls = loader.loadClass("org.jboss.vfs.VirtualFile");
    getPhysicalFile =
        lookup.findVirtual(virtualFileCls, "getPhysicalFile", MethodType.methodType(File.class));
    getName = lookup.findVirtual(virtualFileCls, "getName", MethodType.methodType(String.class));
  }

  private JbossVirtualFileHelper() {
    getPhysicalFile = null;
    getName = null;
  }

  public File getPhysicalFile(Object virtualFile) {
    try {
      return (File) getPhysicalFile.invoke(virtualFile);
    } catch (Throwable e) {
      throw new UndeclaredThrowableException(e);
    }
  }

  public String getName(Object virtualFile) {
    try {
      return (String) getName.invoke(virtualFile);
    } catch (Throwable e) {
      throw new UndeclaredThrowableException(e);
    }
  }

  public static URI getJbossVfsPath(URL location) {
    JbossVirtualFileHelper jbossVirtualFileHelper = JbossVirtualFileHelper.jbossVirtualFileHelper;
    if (jbossVirtualFileHelper == JbossVirtualFileHelper.FAILED_HELPER) {
      return null;
    }

    Object virtualFile;
    try {
      virtualFile = location.openConnection().getContent();
    } catch (IOException e) {
      // silently ignored
      return null;
    }
    if (virtualFile == null) {
      return null;
    }

    if (jbossVirtualFileHelper == null) {
      try {
        jbossVirtualFileHelper =
            JbossVirtualFileHelper.jbossVirtualFileHelper =
                new JbossVirtualFileHelper(virtualFile.getClass().getClassLoader());
      } catch (Exception e) {
        log.warn("Error preparing for inspection of jboss virtual files", e);
        return null;
      }
    }

    // call VirtualFile.getPhysicalFile
    File physicalFile = jbossVirtualFileHelper.getPhysicalFile(virtualFile);
    if (physicalFile.isFile() && physicalFile.getName().endsWith(".jar")) {
      return physicalFile.toURI();
    } else {
      log.warn("Physical file {} is not a jar", physicalFile);
    }

    // not sure what this is about, but it's what the old code used to do
    // this is not correct as a general matter, since getName returns the virtual name,
    // which may not match the physical name
    // The original comment reads:
    // "physical file returns 'content' folder, we manually resolve to the actual jar location"
    String fileName = jbossVirtualFileHelper.getName(virtualFile);
    physicalFile = new File(physicalFile.getParentFile(), fileName);
    if (physicalFile.isFile()) {
      return physicalFile.toURI();
    }
    return null;
  }
}
