Jar file has a structure similar to the agent jar, but contains nested classes

```bash
jar -tf jar-with-nested-classes-jdk8
META-INF/
META-INF/MANIFEST.MF
prefix/
prefix/p/
prefix/p/EnclosingClass.classdata
prefix/p/EnclosingClass$StaticInnerClass.classdata
prefix/p/EnclosingClass$InnerClass.classdata
```
