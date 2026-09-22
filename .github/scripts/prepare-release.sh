#!/usr/bin/env bash
set -euo pipefail
: "${GH_TOKEN:?Set DEPS_TOKEN with Contents read access to TF-Minecraft/server-assets}"
ref=a8a0efde5c58ef77d5ca085e422aa2c3e9565c5e
mkdir -p libs
curl --fail --location --silent --show-error -H "Authorization: Bearer $GH_TOKEN" -H "Accept: application/vnd.github.raw+json" "https://api.github.com/repos/TF-Minecraft/server-assets/contents/jars/4241c14a7727/gson-2.10.1.jar?ref=$ref" > "libs/gson-2.10.1.jar"
curl --fail --location --silent --show-error -H "Authorization: Bearer $GH_TOKEN" -H "Accept: application/vnd.github.raw+json" "https://api.github.com/repos/TF-Minecraft/server-assets/contents/jars/c84700df5942/MMOItems-6.10.jar?ref=$ref" > "libs/MMOItems-6.10.jar"
curl --fail --location --silent --show-error -H "Authorization: Bearer $GH_TOKEN" -H "Accept: application/vnd.github.raw+json" "https://api.github.com/repos/TF-Minecraft/server-assets/contents/jars/660ff2a6ec86/MythicLib-1.7.jar?ref=$ref" > "libs/MythicLib-1.7.jar"
bash .github/scripts/install-local-dependencies.sh "$@"
