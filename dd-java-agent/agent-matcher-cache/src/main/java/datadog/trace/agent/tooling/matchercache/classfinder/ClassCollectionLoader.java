/*
 * Copyright (c) 2016 Erik Håkansson, http://squark.io
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * Copyright (c) 2016 Erik Håkansson, http://squark.io
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package datadog.trace.agent.tooling.matchercache.classfinder;

import java.util.HashMap;
import java.util.Map;

public class ClassCollectionLoader extends ClassLoader {

  private final ClassCollection classCollection;
  private final Map<String, Class<?>> loadedClasses = new HashMap<>();
  private final int javaMajorVersion;

  public ClassCollectionLoader(ClassCollection classCollection, int javaMajorVersion) {
    this.classCollection = classCollection;
    this.javaMajorVersion = javaMajorVersion;
  }

  @Override
  public Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
    synchronized (getClassLoadingLock(name)) {
      Class<?> found = null;
      if (!name.startsWith("datadog.")) {
        try {
          found = super.loadClass(name, resolve);
        } catch (ClassNotFoundException | SecurityException e) {
          /* ignore */
        }
      }
      if (found == null) {
        found = getLoadedClass(name, resolve);
      }
      if (found != null) {
        return found;
      }
      throw new ClassNotFoundException(name);
    }
  }

  private Class<?> getLoadedClass(String className, boolean resolve) throws ClassNotFoundException {
    synchronized (getClassLoadingLock(className)) {
      if (loadedClasses.containsKey(className)) {
        // return already defined class
        return loadedClasses.get(className);
      }
      ClassData classData = classCollection.findClass(className);
      if (classData == null) {
        return null;
      }
      // define the new class
      definePackageForClass(className);
      Class<?> loadedClass = findLoadedClass(className);
      if (loadedClass == null) {
        byte[] classBytes = classData.classBytes(javaMajorVersion);
        if (classBytes == null) {
          throw new ClassNotFoundException(className);
        }
        try {
          loadedClass =
              defineClass(
                  className,
                  classBytes,
                  0,
                  classBytes.length,
                  this.getClass().getProtectionDomain());
        } catch (NoClassDefFoundError | IncompatibleClassChangeError e) {
          throw new ClassNotFoundException(className, e);
        }
      }
      loadedClasses.put(className, loadedClass);
      if (resolve) {
        resolveClass(loadedClass);
      }
      return loadedClass;
    }
  }

  private void definePackageForClass(String className) {
    int classNameStartsAt = className.lastIndexOf('.');
    classNameStartsAt = Math.max(classNameStartsAt, 0);
    String pkgname = className.substring(0, classNameStartsAt);
    Package pkg = getPackage(pkgname);
    if (pkg == null) {
      definePackage(pkgname, null, null, null, null, null, null, null);
    }
  }
}
