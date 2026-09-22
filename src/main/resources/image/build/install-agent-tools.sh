#!/usr/bin/env bash
# The agent harnesses, installed globally so they are on PATH for the agent user — spec §21.
#
# Takes any number of npm specifiers. Deliberately tolerant: a harness that fails to install must
# not fail the whole image build, because the desktop and the shell are still perfectly usable
# without it, and the user can name a different one in oillamp.toml.
set -Eeuo pipefail
[ $# -gt 0 ] || { echo "no agent tools requested"; exit 0; }
for tool in "$@"; do
    if npm install -g --no-fund --no-audit "$tool"; then
        echo "installed $tool"
    else
        echo "WARNING: could not install $tool — the sandbox will work without it" >&2
    fi
done
