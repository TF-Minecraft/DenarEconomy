# denareconomy

Technical documentation is maintained in [TF-Minecraft/Docs](https://github.com/TF-Minecraft/Docs/blob/main/projects/DenarEconomy/README.md).

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
