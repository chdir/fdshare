This is a source code of implementation of an open server for Android (aka fdshare).

Preface
=========

File descriptor sharing is an old thecnique, that have been employed by Unix hackers since file descriptors
became passable between processes. It allows one to avoid copying data between processes and makes it
possible to selectively grant access to protected resources. Let's look at typical ***ugly*** flow of
editing a protected system file (for example, a system `hosts` file), employed by many Android
developers at the time of writing:

* Invoking a binary (often `busybox`) with elevated privileges (e.g. via `su`);
* Reading entire file to memory from the executable's standart output;
* Modifying file contents in memory;
* Invoking a binary with elevated privileges (again!);
* Having the executable read new file contents from it's standart input and write them to the file.

This flow can be optimized to start the executable only once and read/write only parts of file, but
the data still would have to be copied back-and-forth between processes. Now look at this flow:

* Invoking a special-purpose binary with elevated privileges
* Have the binary open a file and pass you it's descriptor
* Do whatever you want with file (read/write/map to memory/pass to other processes)

Nice, right? This approach, called "an open server" in "Advanced Programming in UNIX Environment",
is used in handful of modern software, such as PolicyKit, Xorg and MacOS X `authopen` utility,
yet is barely known to common Android (and not just Android!) developer.

Usage
=======

*TODO*: see source code for detailed documentation

Compatibility
===============

This library was fully tested on Android 9-21 ARM and x86 devices. It may work on older
versions, but installed "su" may not support the used syntax (see comments in code).
Versions below API 9 may require additional actions (such as `chmod`-ing library helper binaries
th make them executable). This will be fixed.

Gotchas and pitfalls
======================

Coming soon.

License
=========

This library is licensed under the Apache License, Version 2.0, see LICENSE file for more details.
