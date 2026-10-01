#!/bin/sh
# oillamp: a single file that contains the whole program, including the Java runtime it needs.
#
# WHAT THIS FILE IS
#
# Everything below the line that reads "__OILLAMP_PAYLOAD_BELOW__" is a compressed archive, not
# text. Everything above it is this script. Your terminal will show the archive as garbage if you
# `cat` the file; that is expected. To read only the part meant for humans:
#
#     sed -n '1,/^__OILLAMP_PAYLOAD_BELOW__$/p' oillamp-djinn
#
# WHAT IT DOES WHEN YOU RUN IT
#
# 1. Works out a directory to unpack itself into, under your cache directory.
# 2. If that directory does not exist yet, unpacks the archive into it. This happens once.
# 3. Replaces itself with the Java runtime it just unpacked, running the program.
#
# The directory name contains a fingerprint of this exact file, so two different builds of oillamp
# never share an unpacked copy, and upgrading is just replacing this file.
#
# Nothing is installed. Nothing is written outside the cache directory. To uninstall oillamp,
# delete this file and delete the cache directory printed by `oillamp --where`.

set -eu

# Substituted at build time by the `singleFile` task in build.gradle.
VERSION="@VERSION@"
FINGERPRINT="@FINGERPRINT@"

# The cache directory follows the XDG Base Directory specification, which is what "$HOME/.cache"
# means on Linux. HOME can be unset in a cron job or a container, so there is a fallback.
if [ -n "${XDG_CACHE_HOME:-}" ]; then
    unpacked="$XDG_CACHE_HOME/oillamp/$VERSION-$FINGERPRINT"
elif [ -n "${HOME:-}" ]; then
    unpacked="$HOME/.cache/oillamp/$VERSION-$FINGERPRINT"
else
    unpacked="/tmp/oillamp-$(id -u)/$VERSION-$FINGERPRINT"
fi

if [ "${1:-}" = "--where" ]; then
    printf '%s\n' "$unpacked"
    exit 0
fi

if [ ! -x "$unpacked/runtime/bin/java" ]; then
    # Unpack into a temporary directory beside the target and then rename it. Renaming a directory
    # within one filesystem is atomic, so two oillamp commands started at the same moment cannot
    # produce a half-unpacked directory for each other to run.
    staging="$unpacked.incoming.$$"
    trap 'rm -rf "$staging"' EXIT INT TERM
    mkdir -p "$staging" || {
        echo "oillamp: cannot create $staging - is the filesystem full or read-only?" >&2
        exit 70
    }
    # The archive begins on the line after the marker, so this prints the file from that line
    # onwards and hands it to tar. `tail -n +N` counts lines, and the marker is the last line of
    # text in the file, so every byte after it belongs to the archive.
    start=$(awk '/^__OILLAMP_PAYLOAD_BELOW__$/ { print NR + 1; exit }' "$0")
    if [ -z "$start" ]; then
        echo "oillamp: this file is truncated - the archive marker is missing." >&2
        echo "         Copy it again; a partial download or a text-mode transfer will do this." >&2
        exit 70
    fi
    tail -n +"$start" "$0" | tar -xzf - -C "$staging" || {
        echo "oillamp: the archive inside this file could not be unpacked." >&2
        echo "         Copy it again; a partial download or a text-mode transfer will do this." >&2
        exit 70
    }
    mkdir -p "$(dirname "$unpacked")"
    # A directory for this build without a Java runtime in it is damaged: an unpack cut short, or
    # a cache tidied by hand. Nothing can be running from it, so it is replaced.
    if [ -e "$unpacked" ] && [ ! -x "$unpacked/runtime/bin/java" ]; then
        rm -rf "$unpacked"
    fi
    # -T: rename onto that exact name, or fail. Without it, mv moves the copy *inside* a
    # directory that already exists by then. A losing race is not an error: it means another
    # oillamp finished unpacking first, and what it unpacked is identical to this, because the
    # directory name contains the fingerprint.
    mv -T "$staging" "$unpacked" 2>/dev/null || true
    trap - EXIT INT TERM
    rm -rf "$staging"
    [ -x "$unpacked/runtime/bin/java" ] || {
        echo "oillamp: unpacked $unpacked but found no Java runtime in it." >&2
        exit 70
    }
fi

# `exec` replaces this shell with the Java process rather than starting it as a child. That is
# deliberate and it matters: oillamp runs a session in the foreground and ends it when you press
# Ctrl-C, so the signal has to arrive at the program itself and not at a wrapper that would have
# to forward it.
exec "$unpacked/runtime/bin/java" \
     -XX:+UseSerialGC -Xshare:auto \
     -cp "$unpacked/lib/*" \
     dev.oillamp.OilLamp "$@"

__OILLAMP_PAYLOAD_BELOW__
