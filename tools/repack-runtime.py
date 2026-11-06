#!/usr/bin/env python3
"""The runtime (glibc, Mesa) is built for /data/data/app.pocketfoundry/files/usr. Factory's package is com.factory:
every occurrence of the package name in file contents and symlink targets is replaced by one of the same length,
padded with '/' (repeated slashes are harmless in paths), so binaries stay valid.
Usage: repack-runtime.py <in runtime.tar.xz> <out runtime.tar.xz> <package>"""
import io, lzma, sys, tarfile

src, dst, package = sys.argv[1], sys.argv[2], sys.argv[3]
OLD = b"app.pocketfoundry"
if len(package) > len(OLD):
    sys.exit("package name too long: %s" % package)
NEW = package.encode() + b"/" * (len(OLD) - len(package))
OLD_PATH, NEW_PATH = b"/data/data/" + OLD, b"/data/data/" + NEW
count = 0
with tarfile.open(src, "r:xz") as tin, lzma.open(dst, "wb", preset=6) as raw, tarfile.open(fileobj=raw, mode="w|", format=tarfile.GNU_FORMAT) as tout:
    for member in tin:
        data = None
        if member.isfile():
            data = tin.extractfile(member).read()
            if OLD_PATH in data:
                count += data.count(OLD_PATH)
                data = data.replace(OLD_PATH, NEW_PATH)
            if OLD in data:  # e.g. the glibc version string "GNU libc for Android/app.pocketfoundry"
                count += data.count(OLD)
                data = data.replace(OLD, NEW)
        if member.issym() or member.islnk():
            member.linkname = member.linkname.replace(OLD_PATH.decode(), NEW_PATH.decode())
        tout.addfile(member, io.BytesIO(data) if data is not None else None)
print("replaced", count, "occurrences")
