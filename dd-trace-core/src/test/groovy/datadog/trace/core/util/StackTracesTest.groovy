package datadog.trace.core.util


import spock.lang.Specification

class StackTracesTest extends Specification {

  def "test stack trace truncation: #limit"() {
    given:
    def trace = """
Exception in thread "main" com.example.app.MainException: Unexpected application failure
    at com.example.app.Application\$Runner.run(Application.java:102)
    at com.example.app.Application.lambda\$start\$0(Application.java:75)
    at java.base/java.util.Optional.ifPresent(Optional.java:178)
    at com.example.app.Application.start(Application.java:74)
    at com.example.app.Main.main(Main.java:21)
    at sun.reflect.NativeMethodAccessorImpl.invoke0(Native Method)
    at sun.reflect.NativeMethodAccessorImpl.invoke(NativeMethodAccessorImpl.java:62)
    at sun.reflect.DelegatingMethodAccessorImpl.invoke(DelegatingMethodAccessorImpl.java:43)
    at java.base/java.lang.reflect.Method.invoke(Method.java:566)
    at com.example.launcher.Bootstrap.run(Bootstrap.java:39)
    at com.example.launcher.Bootstrap.main(Bootstrap.java:25)
    at com.example.internal.\$Proxy1.start(Unknown Source)
    at com.example.internal.Initializer\$1.run(Initializer.java:47)
    at com.example.internal.Initializer.lambda\$init\$0(Initializer.java:38)
    at java.base/java.util.concurrent.Executors\$RunnableAdapter.call(Executors.java:515)
    at java.base/java.util.concurrent.FutureTask.run(FutureTask.java:264)
    at java.base/java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1128)
    at java.base/java.util.concurrent.ThreadPoolExecutor\$Worker.run(ThreadPoolExecutor.java:628)
    at java.base/java.lang.Thread.run(Thread.java:834)
    at com.example.synthetic.Helper.access\$100(Helper.java:14)
Caused by: com.example.db.DatabaseException: Failed to load user data
    at com.example.db.UserDao.findUser(UserDao.java:88)
    at com.example.db.UserDao.lambda\$cacheLookup\$1(UserDao.java:64)
    at com.example.cache.Cache\$Entry.computeIfAbsent(Cache.java:111)
    at com.example.cache.Cache.get(Cache.java:65)
    at com.example.service.UserService.loadUser(UserService.java:42)
    at com.example.service.UserService.lambda\$loadUserAsync\$0(UserService.java:36)
    at com.example.util.SafeRunner.run(SafeRunner.java:27)
    at java.base/java.util.concurrent.Executors\$RunnableAdapter.call(Executors.java:515)
    at java.base/java.util.concurrent.FutureTask.run(FutureTask.java:264)
    at java.base/java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1128)
    at java.base/java.util.concurrent.ThreadPoolExecutor\$Worker.run(ThreadPoolExecutor.java:628)
    at java.base/java.lang.Thread.run(Thread.java:834)
    at com.example.synthetic.UserDao\$1.run(UserDao.java:94)
    at com.example.synthetic.UserDao\$1.run(UserDao.java:94)
    at com.example.db.ConnectionManager.getConnection(ConnectionManager.java:55)
Suppressed: java.io.IOException: Resource cleanup failed
    at com.example.util.ResourceManager.close(ResourceManager.java:23)
    at com.example.service.UserService.lambda\$loadUserAsync\$0(UserService.java:38)
    ... 3 more
Caused by: java.nio.file.AccessDeniedException: /data/user/config.json
    at java.base/sun.nio.fs.UnixException.translateToIOException(UnixException.java:90)
    at java.base/sun.nio.fs.UnixException.rethrowAsIOException(UnixException.java:111)
    at java.base/sun.nio.fs.UnixException.rethrowAsIOException(UnixException.java:116)
    at java.base/sun.nio.fs.UnixFileSystemProvider.newByteChannel(UnixFileSystemProvider.java:219)
    at java.base/java.nio.file.Files.newByteChannel(Files.java:375)
    at java.base/java.nio.file.Files.newInputStream(Files.java:489)
    at com.example.util.FileUtils.readFile(FileUtils.java:22)
    at com.example.util.ResourceManager.close(ResourceManager.java:21)
    ... 3 more
"""

    expect:
    StackTraces.truncate(trace, limit) == expected

    where:
    limit | expected
    1000  | """
Exception in thread "main" com.example.app.MainException: Unexpected application failure
	at c.e.a.Application\$Runner.run(Application.java:102)
	at c.e.a.Application.lambda\$start\$0(Application.java:75)
	at j.b.u.Optional.ifPresent(Optional.java:178)
	at c.e.a.Application.start(Application.java:74)
	at c.e.a.Main.main(Main.java:21)
	at s.r.NativeMethodAccessorImpl.invoke0(Native Method)
	at s.r.NativeMethodAccessorImpl.invoke(NativeMethodAccessorImpl.java:62)
	at s.r.Delegat
	... trace centre-cut to 1000 chars ...
ToIOException(UnixException.java:90)
	at j.b.n.f.UnixException.rethrowAsIOException(UnixException.java:111)
	at j.b.n.f.UnixException.rethrowAsIOException(UnixException.java:116)
	at j.b.n.f.UnixFileSystemProvider.newByteChannel(UnixFileSystemProvider.java:219)
	at j.b.n.f.Files.newByteChannel(Files.java:375)
	at j.b.n.f.Files.newInputStream(Files.java:489)
	at c.e.u.FileUtils.readFile(FileUtils.java:22)
	at c.e.u.ResourceManager.close(ResourceManager.java:21)
    ... 3 more
"""
    2500  | """
Exception in thread "main" com.example.app.MainException: Unexpected application failure
	at c.e.a.Application\$Runner.run(Application.java:102)
	at c.e.a.Application.lambda\$start\$0(Application.java:75)
	at j.b.u.Optional.ifPresent(Optional.java:178)
	at c.e.a.Application.start(Application.java:74)
	at c.e.a.Main.main(Main.java:21)
	at s.r.NativeMethodAccessorImpl.invoke0(Native Method)
	at s.r.NativeMethodAccessorImpl.invoke(NativeMethodAccessorImpl.java:62)
	at s.r.DelegatingMethodAccessorImpl.invoke(DelegatingMethodAccessorImpl.java:43)
	... 8 trimmed ...
	at j.b.u.c.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1128)
	at j.b.u.c.ThreadPoolExecutor\$Worker.run(ThreadPoolExecutor.java:628)
	at j.b.l.Thread.run(Thread.java:834)
	at c.e.s.Helper.access\$100(Helper.java:14)
Caused by: com.example.db.DatabaseException: Failed to load user data
	at c.e.d.UserDao.findUser(UserDao.java:88)
	at c.e.d.UserDao.lambda\$cacheLookup\$1(UserDao.java:64)
	at c.e.c.Cache\$Entry.computeIfAbsent(Cache.java:111)
	at c.e.c.Cache.get(Cache.java:65)
	at c.e.s.UserService.loadUser(UserService.java:42)
	at c.e.s.UserService.lambda\$loadUserAsync\$0(UserService.java:36)
	at c.e.u.SafeRunner.run(SafeRunner.java:27)
	at j.b.u.c.Executors\$RunnableAdapter.call(Executors.java:515)
	... 3 trimmed ...
	at j.b.l.Thread.run(Thread.java:834)
	at c.e.s.UserDao\$1.run(UserDao.java:94)
	at c.e.s.UserDao\$1.run(UserDao.java:94)
	at c.e.d.ConnectionManager.getConnection(ConnectionManager.java:55)
Suppressed: java.io.IOException: Resource cleanup failed
	at c.e.u.ResourceManager.close(ResourceManager.java:23)
	at c.e.s.UserService.lambda\$loadUserAsync\$0(UserService.java:38)
    ... 3 more
Caused by: java.nio.file.AccessDeniedException: /data/user/config.json
	at j.b.n.f.UnixException.translateToIOException(UnixException.java:90)
	at j.b.n.f.UnixException.rethrowAsIOException(UnixException.java:111)
	at j.b.n.f.UnixException.rethrowAsIOException(UnixException.java:116)
	at j.b.n.f.UnixFileSystemProvider.newByteChannel(UnixFileSystemProvider.java:219)
	at j.b.n.f.Files.newByteChannel(Files.java:375)
	at j.b.n.f.Files.newInputStream(Files.java:489)
	at c.e.u.FileUtils.readFile(FileUtils.java:22)
	at c.e.u.ResourceManager.close(ResourceManager.java:21)
    ... 3 more
"""
    3000  | """
Exception in thread "main" com.example.app.MainException: Unexpected application failure
	at c.e.a.Application\$Runner.run(Application.java:102)
	at c.e.a.Application.lambda\$start\$0(Application.java:75)
	at j.b.u.Optional.ifPresent(Optional.java:178)
	at c.e.a.Application.start(Application.java:74)
	at c.e.a.Main.main(Main.java:21)
	at s.r.NativeMethodAccessorImpl.invoke0(Native Method)
	at s.r.NativeMethodAccessorImpl.invoke(NativeMethodAccessorImpl.java:62)
	at s.r.DelegatingMethodAccessorImpl.invoke(DelegatingMethodAccessorImpl.java:43)
	at j.b.l.r.Method.invoke(Method.java:566)
	at c.e.l.Bootstrap.run(Bootstrap.java:39)
	at c.e.l.Bootstrap.main(Bootstrap.java:25)
	at c.e.i.\$Proxy1.start(Unknown Source)
	at c.e.i.Initializer\$1.run(Initializer.java:47)
	at c.e.i.Initializer.lambda\$init\$0(Initializer.java:38)
	at j.b.u.c.Executors\$RunnableAdapter.call(Executors.java:515)
	at j.b.u.c.FutureTask.run(FutureTask.java:264)
	at j.b.u.c.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1128)
	at j.b.u.c.ThreadPoolExecutor\$Worker.run(ThreadPoolExecutor.java:628)
	at j.b.l.Thread.run(Thread.java:834)
	at c.e.s.Helper.access\$100(Helper.java:14)
Caused by: com.example.db.DatabaseException: Failed to load user data
	at c.e.d.UserDao.findUser(UserDao.java:88)
	at c.e.d.UserDao.lambda\$cacheLookup\$1(UserDao.java:64)
	at c.e.c.Cache\$Entry.computeIfAbsent(Cache.java:111)
	at c.e.c.Cache.get(Cache.java:65)
	at c.e.s.UserService.loadUser(UserService.java:42)
	at c.e.s.UserService.lambda\$loadUserAsync\$0(UserService.java:36)
	at c.e.u.SafeRunner.run(SafeRunner.java:27)
	at j.b.u.c.Executors\$RunnableAdapter.call(Executors.java:515)
	at j.b.u.c.FutureTask.run(FutureTask.java:264)
	at j.b.u.c.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1128)
	at j.b.u.c.ThreadPoolExecutor\$Worker.run(ThreadPoolExecutor.java:628)
	at j.b.l.Thread.run(Thread.java:834)
	at c.e.s.UserDao\$1.run(UserDao.java:94)
	at c.e.s.UserDao\$1.run(UserDao.java:94)
	at c.e.d.ConnectionManager.getConnection(ConnectionManager.java:55)
Suppressed: java.io.IOException: Resource cleanup failed
	at c.e.u.ResourceManager.close(ResourceManager.java:23)
	at c.e.s.UserService.lambda\$loadUserAsync\$0(UserService.java:38)
    ... 3 more
Caused by: java.nio.file.AccessDeniedException: /data/user/config.json
	at j.b.n.f.UnixException.translateToIOException(UnixException.java:90)
	at j.b.n.f.UnixException.rethrowAsIOException(UnixException.java:111)
	at j.b.n.f.UnixException.rethrowAsIOException(UnixException.java:116)
	at j.b.n.f.UnixFileSystemProvider.newByteChannel(UnixFileSystemProvider.java:219)
	at j.b.n.f.Files.newByteChannel(Files.java:375)
	at j.b.n.f.Files.newInputStream(Files.java:489)
	at c.e.u.FileUtils.readFile(FileUtils.java:22)
	at c.e.u.ResourceManager.close(ResourceManager.java:21)
    ... 3 more
"""
  }
}
