This is a source code of implementation of an open server for Android (aka fdshare).

Preface
========

File descriptor sharing is a technique, that allows one to avoid copying large data between processes
and makes it possible to selectively grant access to various resources. Let's look at typical ***ugly*** flow of
editing a protected system file (for example, a system `hosts` file), employed by many Android
developers at the time of writing:

* Invoking a binary (often `busybox`) with elevated privileges (e.g. via `su`);
* Reading entire file to memory from the executable's standard output;
* Modifying file contents in memory;
* Invoking a binary with elevated privileges (again!);
* Having the executable read new file contents from it's standart input and write them to the file.

This flow can be optimized to start the executable only once and read/write only parts of file, but
the data still would have to be copied back-and-forth between processes. Now just look at this code:

```java
Context context = ...
try (FileDescriptorFactory factory = FileDescriptorFactory.create(context);
     RandomAccessFile file = factory.openRandomAccessFile("/system/etc/hosts"))
{
    Channel ch = file.getChannel();
    MappedByteBuffer mappedFile = ch.map(MapMode.READ_WRITE, 0, file.size);

    // you can do whatever you like now!
} catch (FactoryBrokenException oups) {
    // most likely the root access being denied
    ...
} catch (IOException ioerr) {
    ...
}
```

Nice, right? The underlying approach, referred to as "open server" in [Advanced Programming in UNIX Environment][1],
is used in handful of modern desktop software, such as PolicyKit, Xorg and MacOS X `authopen` utility. It also
what lies behind Android content providers and Storage Access Framework.

Wait, so what is this library for?
===================================

In short this library allows one to leverage root access for:

* Opening arbitrary files and working with their contents;
* Writing a ContentProvider, that offers access to arbitrary files in file system to other processes;
* Opening privileged (below 1024) network ports;
* Hijacking files, pipes and sockets, open by other processes;

all of that without invoking *busybox*, *sh* or like, without moving one's app to */system/app-priv/* and
without writing custom native binaries to launch with *su*. Your app's process does not need to run with
any special rights (such as rooted Dalvik instance etc.), but the device itself must be rooted.

If you don't understand, how is this possible, you may want to read a bit about [Unix file descriptors](https://en.wikipedia.org/wiki/File_descriptor),
as well as their use in Android (I recommend familiarizing yourself with [this post][2], concerning the later).

Usage
======

Add following dependency to your `dependencies` block:

```groovy
dependencies {
    ...
    compile 'net.sf.fdshare:library:0.2.4@aar'
}
```

also make sure, that jCenter is enabled in repository settings:

```groovy
repositories {
    ...
    jcenter()
}
```

Threading
==========
This library is thread-safe.

Opening things with this library may take unpredictable amount of time, depending on countless factors, which are
impossible to predict in advance (e.g., how long will it take for user to grant a root access via permission
request dialog?) In order to work around this complication the library uses a 20-second timeout for each call
to `open`, resulting in FactoryBrokenException if exceeded. It is longer then analogous timeout, used
for similar reasons by [SuperSu][3], and should be sufficient for all practical purposes. You can adjust it to
your liking by setting system property `FileDescriptorFactory#PRIMARY_TIMEOUT`.

If you want to cancel the call to `open` prematurely, just interrupt the thread, that does it
(by interrupting on the Thread,  cancelling the AsyncTask, unsubscribing from Observable etc.)
Doing so will result in IOException being immediately thrown (but the corresponding file may still be open
in background, only to be closed immediately).

Compatibility
==============

This library was fully tested on Android 9-21 ARM and x86 devices. It may work on older
versions, but installed "su" may not support the used syntax (see comments in code).
Versions below API 9 may require additional actions (such as `chmod`-ing library helper binaries
th make them executable). This will be fixed.

License
=========

This library is licensed under the Apache License, Version 2.0, see LICENSE file for more details.

[1]: http://www.apuebook.com
[2]: https://stackoverflow.com/a/30283769/1643723
[3]: http://forum.xda-developers.com/apps/supersu
