Jar file has a structure similar to the agent jar:

```bash
 jar -tf testjar      
META-INF/
META-INF/MANIFEST.MF
parent/
parent/a/
parent/a/A.classdata
parent/a/b/
parent/a/b/B.classdata
parent/a/b/c/
parent/a/b/c/C.classdata
child/
child/x/
child/x/X.classdata
child/x/y/
child/x/y/Y.classdata
child/x/y/z/
child/x/y/z/Z.classdata

```
