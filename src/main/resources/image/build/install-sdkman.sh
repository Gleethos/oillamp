#!/usr/bin/env bash
# Installs SDKMAN, so that any JVM toolchain is one command away inside the sandbox.
#
# The image ships one JDK. A lot of JVM work needs a different one, or a second one beside it, or
# a Groovy, a Gradle or a Maven that does not match what Debian packages. None of that can be
# apt-installed in here, because the root filesystem is read-only and there is no sudo. SDKMAN
# installs into a directory instead, which is exactly the shape this sandbox can accommodate.
#
# It is installed *outside* the agent's home, for the same reason pi's extensions are: /home/agent
# is a bind mount from the lamp, so anything the build writes there is hidden the moment the
# container starts. The entrypoint copies it into the home once per lamp, where it is writable and
# where whatever the agent installs with it outlives the session.
#
# Like the agent harnesses, this may not fail the build. A sandbox without SDKMAN still has a JDK,
# a desktop, a shell and a recording; losing those because a download stalled would be a bad trade
# made automatically.
set -Eeuo pipefail

SDKMAN_DIR=/usr/local/share/oillamp/sdkman
export SDKMAN_DIR

main() {
    # rcupdate=false because the installer would otherwise append its init lines to /root/.bashrc:
    # the wrong user, the wrong home, and read-only at run time anyway. /etc/profile.d/oillamp.sh
    # does the sourcing instead, in the login shell of the user who will actually use it.
    curl -fsSL "https://get.sdkman.io/?rcupdate=false" | bash

    [ -r "$SDKMAN_DIR/bin/sdkman-init.sh" ] \
        || { echo "the installer left no sdkman-init.sh behind" >&2; return 1; }

    # The one upstream default this sandbox changes. An agent reaching the sandbox over ssh cannot
    # answer "Do you want java 25 to be set as default? (Y/n)", so every prompt is a command that
    # hangs until a timeout, with a turn's worth of the agent's thinking already spent on it.
    sed -i 's/^sdkman_auto_answer=.*/sdkman_auto_answer=true/' "$SDKMAN_DIR/etc/config"
    grep -qx 'sdkman_auto_answer=true' "$SDKMAN_DIR/etc/config" \
        || { echo "could not turn off SDKMAN's prompts" >&2; return 1; }

    # The build runs as root and the copy is read by uid 1000.
    chmod -R a+rX "$SDKMAN_DIR"
    echo "installed sdkman $(cat "$SDKMAN_DIR/var/version") for $(cat "$SDKMAN_DIR/var/platform")"
}

main || echo "WARNING: sdkman did not install — the sandbox keeps the image's own JDK" >&2
exit 0
