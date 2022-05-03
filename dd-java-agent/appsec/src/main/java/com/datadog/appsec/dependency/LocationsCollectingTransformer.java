package com.datadog.appsec.dependency;

import java.io.File;
import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.UndeclaredThrowableException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class LocationsCollectingTransformer implements ClassFileTransformer {
  private static final Logger log = LoggerFactory.getLogger(LocationsCollectingTransformer.class);

  private final DependencyServiceImpl dependencyService;
  private final Set<ProtectionDomain> seenDomains =
      Collections.newSetFromMap(new IdentityHashMap<>());

  public LocationsCollectingTransformer(DependencyServiceImpl dependencyService) {
    this.dependencyService = dependencyService;
  }

  @Override
  public byte[] transform(
      ClassLoader loader,
      String className,
      Class<?> classBeingRedefined,
      ProtectionDomain protectionDomain,
      byte[] classfileBuffer) {
    URI uri = null;
    if (protectionDomain == null) {
      return null;
    }
    if (!seenDomains.add(protectionDomain)) {
      return null;
    }

    CodeSource codeSource = protectionDomain.getCodeSource();
    if (codeSource == null) {
      return null;
    }

    URL location = codeSource.getLocation();
    if (location == null) {
      return null;
    }

    if (location.getProtocol().equals("vfs")
    //        && loader.getClass().getName().equals("org.jboss.modules.ModuleClassLoader")
    ) {
      // resolve jboss virtual file system
      try {
        uri = getJbossVfsPath(location);
      } catch (RuntimeException rte) {
        log.debug("Error in call to getJbossVfsPath", rte);
        return null;
      }
    }

    if (uri == null) {
      try {
        uri = location.toURI();
      } catch (URISyntaxException e) {
        log.warn("Error converting URL to URI", e);
        // silently ignored
      }
    }

    if (uri != null) {
      dependencyService.addURI(uri);
    }

    // returning 'null' is the best way to indicate that no transformation has been done.
    return null;
  }

  static class JbossVirtualFileHelper {
    private final MethodHandle getPhysicalFile;
    private final MethodHandle getName;

    public static final JbossVirtualFileHelper FAILED_HELPER = new JbossVirtualFileHelper();

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
  }

  private volatile JbossVirtualFileHelper jbossVirtualFileHelper;

  private URI getJbossVfsPath(URL location) {
    JbossVirtualFileHelper jbossVirtualFileHelper = this.jbossVirtualFileHelper;
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
            this.jbossVirtualFileHelper =
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
      log.info("Physical file {} is not a jar", physicalFile);
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
