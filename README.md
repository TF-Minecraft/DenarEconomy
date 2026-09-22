# denareconomy

Technical documentation is maintained in [TF-Minecraft/docs](https://github.com/TF-Minecraft/docs/tree/main/projects/denareconomy).

Use that project index for setup, configuration, architecture, integration and testing guides. This repository contains the source and project-specific assets.

## TLibs build dependency

TLibs is a versioned Maven `provided` dependency. From this repository, prepare
it once with the shared installer, then build as usual:

```sh
python3 ../tlibs/tools/install-dependency.py --pom pom.xml
mvn clean verify
```

See [TLibs dependency setup](https://github.com/TF-Minecraft/TLibs/blob/5da8e77d0e0696bbff7d7064a2644072da9c6428/DEPENDENCIES.md)
for public release installation, offline builds and rollback.
Other declared build dependencies still need their usual preparation.

Builds and server runtime require Java 25. Local builds default to [TLibs 1.1.0](https://github.com/TF-Minecraft/TLibs/releases/tag/v1.1.0); CI resolves the latest published stable TLibs release for each build, verifies its checksum, and uses its exact version throughout that job.

## Shared plugin dependencies

Build and release workflows install checksum-verified plugin releases through
[TLibs' shared installer](https://github.com/TF-Minecraft/TLibs/blob/main/DEPENDENCIES.md).
CI selects the latest published versions; local builds use the explicit Maven
version properties. Shared plugins use `provided` scope and remain separate
server plugins. Each build records exact versions and checksums in
`.build/plugin-dependencies.json` alongside its JAR.

From this checkout, with the TLibs repository next to it:

```sh
python3 ../tlibs/tools/install-plugins.py --pom pom.xml
```

Prepare any remaining third-party inputs with `.github/scripts/prepare-release.sh`
before running Maven. Source-unavailable AdvancedCrafting and MusicalInstruments
inputs remain private and checksum-pinned wherever declared; see the installer
documentation for authentication and reproducible rebuilds.
