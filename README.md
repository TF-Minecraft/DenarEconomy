# DenarEconomy

> Coins, pouches, and banking for TF-Minecraft.

DenarEconomy provides the money players carry, store, and spend throughout the server. It connects physical coin items with a personal pouch and bank account, so denars can move between an inventory, everyday transactions, and longer-term savings.

The plugin also supplies the shared economy used by other TF-Minecraft systems, from shops to games. Those experiences can award or spend the same currency while keeping each player's balances together.

## Features

- **Pouch and bank accounts** — keep carried money separate from money held in the bank, with deposits and withdrawals between them.
- **Physical currency** — turn pouch money into coin items and collect supported currency items back into the economy.
- **Multiple denominations** — represent values with gold and silver coins, handfuls, stacks, and pouches of coins.
- **Coins in the world** — drop money from your pouch as physical coins for others to collect.
- **Balance visibility** — inspect personal balances and view a leaderboard of the wealthiest accounts.
- **Shared earnings** — give other plugins a common way to award money and respond to income, banking, and material deposits.

## Documentation

[Project documentation](https://github.com/TF-Minecraft/Docs/blob/main/projects/DenarEconomy/README.md)

Technical documentation is maintained in [TF-Minecraft/Docs](https://github.com/TF-Minecraft/Docs).

## Tests and coverage

With Java 21 and the pinned dependencies installed (the same preparation used in
`.github/workflows/build.yml`), run:

```sh
mvn -B --no-transfer-progress clean verify
```

JaCoCo reports all production classes, without coverage exclusions. Open
`target/site/jacoco/index.html` for the HTML report; `jacoco.xml` and `jacoco.csv`
are in the same directory. CI uploads the report as a `coverage-report` artifact.
Tests use MockBukkit, Mockito, and real temporary files; legacy relative database
paths are isolated under `target/test-runtime`. Run Maven invocations sequentially
within a checkout because they share the build directory.

The suite reaches **100% line, branch, and instruction coverage** across all
30 production classes. `verify` enforces 100% for each of these metrics.
Redundant private checks and unreachable enum defaults have been simplified;
public APIs and money-drop recovery checks remain. Fault-injection tests cover
the recovery paths even when an adapter violates Bukkit's non-null contracts.
Regression tests prevent fractional-cent withdrawals from creating forbidden
overdrafts, reject fractional-cent payments/conversions, preserve pouch balances
when a payment, conversion, or PvP death cannot produce coins, and honor cancelled
coin pickups. Conversions require exact change and drop inventory overflow.
Taxable earnings retain their full value unless a listener explicitly sets tax.
Account I/O errors propagate instead of creating zero balances or discarding unsaved
sessions. Saves write a temporary sibling and require atomic replacement; failed
offline changes restore the prior balance. Shutdown retries all retained accounts
and continues after individual failures. Persistent storage failures still require
operator attention before the server process exits; retained memory is not durable.

These tests validate plugin logic and simulated Bukkit interactions, not a live
Paper server or the internals of MMOItems/TLibs. The filesystem permission cases
require POSIX permissions and an unprivileged user, as provided by CI.

## License

Copyright (c) 2026 TF-Minecraft contributors.

TF-Minecraft-authored material in this repository is licensed under the
[Artistic License 2.0](LICENSE). Third-party dependencies and bundled material
retain their own licenses.
