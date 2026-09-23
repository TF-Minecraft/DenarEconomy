#!/usr/bin/env bash
set -euo pipefail
: "${GH_TOKEN:?Set DEPS_TOKEN with Contents read access to TF-Minecraft/server-assets}"
ref=4b80431398e4ff35d703cad915b7ee4e5924a763
mkdir -p libs
curl --fail --location --silent --show-error -H "Authorization: Bearer $GH_TOKEN" -H "Accept: application/vnd.github.raw+json" "https://api.github.com/repos/TF-Minecraft/server-assets/contents/jars/2cbd119bf196/gson-2.14.0.jar?ref=$ref" > "libs/gson-2.14.0.jar"
curl --fail --location --silent --show-error -H "Authorization: Bearer $GH_TOKEN" -H "Accept: application/vnd.github.raw+json" "https://api.github.com/repos/TF-Minecraft/server-assets/contents/jars/a37f7789fcdc/MMOItems-6.10.1-SNAPSHOT.jar?ref=$ref" > "libs/MMOItems-6.10.1-SNAPSHOT.jar"
curl --fail --location --silent --show-error -H "Authorization: Bearer $GH_TOKEN" -H "Accept: application/vnd.github.raw+json" "https://api.github.com/repos/TF-Minecraft/server-assets/contents/jars/225aa7f75d4e/MythicLib-1.7.1-SNAPSHOT.jar?ref=$ref" > "libs/MythicLib-1.7.1-SNAPSHOT.jar"
bash .github/scripts/install-local-dependencies.sh "$@"
