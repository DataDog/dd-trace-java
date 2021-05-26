package io.sqreen.testapp.sampleapp

/* adapted from https://stackoverflow.com/a/6424879/127724 */
class ChildFirstURLClassLoader extends URLClassLoader {

  private ClassLoader system

  ChildFirstURLClassLoader(URL[] classpath, ClassLoader parent) {
    super(classpath, parent)
    system = getSystemClassLoader()
  }

  @Override
  protected synchronized Class<?> loadClass(String name, boolean resolve)
  throws ClassNotFoundException {
    // First, check if the class has already been loaded
    Class<?> c = findLoadedClass(name)
    if (c == null) {
      try {
        // checking local
        c = findClass(name)
      } catch (ClassNotFoundException e) {
        // checking parent
        // This call to loadClass may eventually call findClass again, in case the parent doesn't find anything.
        c = super.loadClass(name, resolve)
      }
    }
    if (resolve) {
      resolveClass(c)
    }
    return c
  }

  @Override
  URL getResource(String name) {
    URL url = null
    if (system != null) {
      url = system.getResource(name)
    }
    if (url == null) {
      url = findResource(name)
      if (url == null) {
        // This call to getResource may eventually call findResource again, in case the parent doesn't find anything.
        url = super.getResource(name)
      }
    }
    url
  }

  @Override
  Enumeration<URL> getResources(String name) throws IOException {
    /**
     * Similar to super, but local resources are enumerated before parent resources
     */
    Enumeration<URL> systemUrls = null
    if (system != null) {
      systemUrls = system.getResources(name)
    }
    Enumeration<URL> localUrls = findResources(name)
    Enumeration<URL> parentUrls = null
    if (getParent() != null) {
      parentUrls = getParent().getResources(name)
    }
    final List<URL> urls = new ArrayList<URL>()
    if (systemUrls != null) {
      while(systemUrls.hasMoreElements()) {
        urls.add(systemUrls.nextElement())
      }
    }
    if (localUrls != null) {
      while (localUrls.hasMoreElements()) {
        urls.add(localUrls.nextElement())
      }
    }
    if (parentUrls != null) {
      while (parentUrls.hasMoreElements()) {
        urls.add(parentUrls.nextElement())
      }
    }
    return new Enumeration<URL>() {
        Iterator<URL> iter = urls.iterator()

        boolean hasMoreElements() {
          return iter.hasNext()
        }
        URL nextElement() {
          return iter.next()
        }
      }
  }

  @Override
  InputStream getResourceAsStream(String name) {
    URL url = getResource(name)
    try {
      return url != null ? url.openStream() : null
    } catch (IOException e) { }
    return null
  }

}

