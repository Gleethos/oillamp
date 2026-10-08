#!/bin/sh
# Runs in the container made from the Containerfile beside it, with /work holding `files`, what
# goes into the flatpak's /app, and receiving `genies.flatpak`. /root/.local/share/flatpak is a
# volume that keeps the freedesktop runtime between builds: it is downloaded once.
set -eu
id=dev.oillamp.Genies
runtime=org.freedesktop.Platform
branch=25.08

flatpak remote-add --user --if-not-exists flathub https://dl.flathub.org/repo/flathub.flatpakrepo
flatpak install --user --noninteractive --or-update flathub "$runtime//$branch"

rm -rf /tmp/build /tmp/repo
# Nothing is compiled, so the runtime serves as the SDK too.
flatpak build-init /tmp/build "$id" "$runtime" "$runtime" "$branch"
cp -a /work/files/. /tmp/build/files/
flatpak build-finish /tmp/build --command=genies --talk-name=org.freedesktop.Flatpak
flatpak build-export /tmp/repo /tmp/build
flatpak build-bundle /tmp/repo /work/genies.flatpak "$id" --runtime-repo=https://dl.flathub.org/repo/flathub.flatpakrepo
