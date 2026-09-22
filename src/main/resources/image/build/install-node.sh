#!/usr/bin/env bash
# Node from NodeSource, because trixie's is too old for the agent harnesses — spec §15.2.
set -Eeuo pipefail
major="${1:-24}"
curl -fsSL "https://deb.nodesource.com/setup_${major}.x" | bash -
apt-get install -y --no-install-recommends nodejs
rm -rf /var/lib/apt/lists/*
node --version
