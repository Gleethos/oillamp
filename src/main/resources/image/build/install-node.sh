#!/usr/bin/env bash
# Installs Node.js from NodeSource, because Debian trixie's version is too old for the agent harnesses.
set -Eeuo pipefail
major="${1:-24}"
curl -fsSL "https://deb.nodesource.com/setup_${major}.x" | bash -
apt-get install -y --no-install-recommends nodejs
rm -rf /var/lib/apt/lists/*
node --version
