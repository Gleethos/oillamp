# Every login shell in the sandbox starts here — design spec Appendix E.
#
# This is where the agent's environment is made to tell the truth: there is no DNS and no route,
# so any tool that does not honour the proxy variables will fail, and the ones that do must find
# them already set. Getting this file wrong looks like "the network is broken" to the agent.

[ -r /oillamp/session/runtime.env ] && set -a && . /oillamp/session/runtime.env && set +a

# sshd deliberately does not forward the client's locale and the image's ENV does not reach a
# login shell, so without this every GUI application starts with a "'C' is not a UTF-8 locale"
# warning and non-ASCII output is mangled. Seen in the first screenshot ever taken in here.
export LANG=en_US.UTF-8 LC_ALL=en_US.UTF-8

export XDG_RUNTIME_DIR=/run/agent
export WAYLAND_DISPLAY=/run/lamp/wayland-1 DISPLAY=:0 XDG_SESSION_TYPE=wayland
export GDK_BACKEND=wayland,x11 QT_QPA_PLATFORM='wayland;xcb' MOZ_ENABLE_WAYLAND=1
# Without this, Swing windows are drawn with a second set of decorations under Xwayland.
export _JAVA_AWT_WM_NONREPARENTING=1
export DBUS_SESSION_BUS_ADDRESS=unix:path=/run/agent/bus

export HTTP_PROXY=http://127.0.0.1:${OILLAMP_PROXY_PORT:-3128}
export HTTPS_PROXY=$HTTP_PROXY
export http_proxy=$HTTP_PROXY https_proxy=$HTTPS_PROXY
export NO_PROXY=localhost,127.0.0.1,::1 no_proxy=localhost,127.0.0.1,::1
export NODE_USE_ENV_PROXY=1

# ~/libs is on both paths so that System.loadLibrary finds what the agent put there, with no
# extra flags — the agent guide promises this.
export LD_LIBRARY_PATH="$HOME/libs${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
export JAVA_TOOL_OPTIONS="-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=${OILLAMP_PROXY_PORT:-3128} -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=${OILLAMP_PROXY_PORT:-3128} -Dhttp.nonProxyHosts=localhost|127.0.0.1 -Djava.library.path=$HOME/libs"
export PATH="$HOME/.local/bin:$PATH"

# Debian marks its Python installation externally-managed (PEP 668), so a plain `pip install`
# refuses to do anything — and the container's root filesystem is read-only, so the escape hatch
# of installing system-wide would fail too. Together these send pip into ~/.local, which is the
# agent's own home and therefore both writable and kept between sessions. An agent that makes a
# virtualenv has to `unset PIP_USER`, which the agent guide says.
export PIP_USER=1 PIP_BREAK_SYSTEM_PACKAGES=1

# SDKMAN, for the JDK, Groovy, Gradle or Maven version a project actually asks for — none of which
# can be apt-installed in here. The copy that matters is the one in the agent's home, put there by
# the entrypoint, because that is the only writable and persistent place: `sdk install java 21`
# lands under ~/.sdkman and is still there next session.
#
# Sourcing the init script is what defines the `sdk` function and puts installed candidates on
# PATH. It makes no network calls of its own, so this costs a login nothing when the agent has
# installed nothing. It is bash-only, hence the guard.
export SDKMAN_DIR="$HOME/.sdkman"
if [ -n "${BASH_VERSION:-}" ] && [ -r "$SDKMAN_DIR/bin/sdkman-init.sh" ]; then
    . "$SDKMAN_DIR/bin/sdkman-init.sh"
fi

# Asked, not assumed. This line used to state "network via policy proxy" unconditionally, and
# said so just as loudly in a sandbox where nothing was listening on the proxy port at all — so
# the agent read "you have network", tried, and failed for reasons the banner had denied.
oillamp_network_state() {
    if (exec 3<>"/dev/tcp/127.0.0.1/${OILLAMP_PROXY_PORT:-3128}") 2>/dev/null; then
        exec 3<&- 3>&-
        echo "network via policy proxy"
    else
        echo "NO network (nothing is listening on the proxy port)"
    fi
}

if [ -t 1 ] && [ -z "${OILLAMP_BANNER_SHOWN:-}" ]; then
    export OILLAMP_BANNER_SHOWN=1
    cat <<EOB
🪔 lamp ${OILLAMP_LAMP_NAME:-?} — desktop ${OILLAMP_DISPLAY_WIDTH:-?}x${OILLAMP_DISPLAY_HEIGHT:-?} (${OILLAMP_RENDERER:-?}), $(oillamp_network_state)
   Read ~/AGENTS.md for how this sandbox works. Put repos in ~/workspace, native libs in ~/libs.
EOB
fi
