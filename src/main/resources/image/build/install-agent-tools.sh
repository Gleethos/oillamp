#!/usr/bin/env bash
# The agent harnesses, installed into the image so the agent has them the moment it logs in.
#
# Nothing here may fail the build. A sandbox without an agent harness is still a working sandbox
# with a desktop, a shell and a recording, and the user can install a harness themselves or name
# a different one in oillamp.toml. A build that failed because npm was briefly unreachable would
# take all of that away, so every step reports its failure and carries on.
#
# Takes friendly names ("pi", "opencode") or raw npm specifiers. The friendly names are what a
# user writes in oillamp.toml; the mapping to a published package lives here because that is what
# changes when a project renames or moves.
set -Eeuo pipefail

# Where pi looks for extensions when PI_CODING_AGENT_DIR points at it (pi's own convention).
PI_DIR=/usr/local/share/oillamp/pi

# Extensions are installed at build time so they are there from the first session. (`pi install`
# also works inside the sandbox, through the egress proxy.)
PI_EXTENSIONS_DEFAULT="git:github.com/edenai/pi-edenai"

specifier_for() {
    case "$1" in
        # Verified against the registry: `pi` is the binary, the package is scoped.
        pi)       echo "@earendil-works/pi-coding-agent" ;;
        opencode) echo "opencode-ai" ;;
        *)        echo "$1" ;;
    esac
}

install_tools() {
    for tool in "$@"; do
        specifier=$(specifier_for "$tool")
        if npm install -g --no-fund --no-audit "$specifier"; then
            echo "installed $tool ($specifier)"
        else
            echo "WARNING: could not install $tool ($specifier) — the sandbox works without it" >&2
        fi
    done
}

# pi's extensions are installed into an image directory rather than into the agent's home,
# because the agent's home is a bind mount from the lamp and would hide anything put there at
# build time. The entrypoint seeds a copy into the home at session start, where pi finds it and
# the agent can add to it.
install_pi_extensions() {
    command -v pi >/dev/null 2>&1 || { echo "pi is not installed; no extensions to add"; return 0; }
    mkdir -p "$PI_DIR"
    for extension in "$@"; do
        if PI_CODING_AGENT_DIR="$PI_DIR" pi install "$extension" </dev/null; then
            echo "installed pi extension $extension"
        else
            echo "WARNING: could not install pi extension $extension — pi works without it" >&2
        fi
    done
    chmod -R a+rX "$PI_DIR" 2>/dev/null || true
}

main() {
    [ $# -gt 0 ] || { echo "no agent tools requested"; return 0; }
    install_tools "$@"
    # shellcheck disable=SC2086
    install_pi_extensions ${PI_EXTENSIONS:-$PI_EXTENSIONS_DEFAULT}
}

# Never fatal: `|| true` at the top level, so the layer that runs this always succeeds.
main "$@" || echo "WARNING: agent tooling did not fully install — the sandbox is unaffected" >&2
exit 0
