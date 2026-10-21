# DenarEconomy

> Coins, pouches, and banking for TF-Minecraft.

DenarEconomy provides the money players carry, store, and spend throughout the server. It connects physical coin items with a personal pouch and bank account, so denars can move between an inventory, everyday transactions, and longer-term savings.

Money belongs to characters: each character has its own pouch and bank, and switching character switches purse. The plugin also supplies the shared economy used by other TF-Minecraft systems, from shops to games, through one API for paying, charging and taxing characters.

## Features

- **Pouch and bank accounts** — each character keeps carried money separate from money held in the bank, with deposits and withdrawals between them.
- **Transactional ledger** — every balance and payment is kept in one SQLite ledger, so a payment applies completely or not at all, and never twice.
- **Physical currency** — turn pouch money into coin items and collect supported currency items back into the economy.
- **Multiple denominations** — represent values with gold and silver coins, handfuls, stacks, and pouches of coins.
- **Coins in the world** — drop money from your pouch as physical coins for others to collect.
- **Balance visibility** — inspect your character's balances and recent postings, and view a leaderboard of the wealthiest characters.
- **Admin credits** — staff can credit the bank or pouch of the character a player is playing, read any character's ledger, dead or alive, and correct balances between accounts.
- **Estates** — when a dead character's estate is decided, its pouch and bank go to the heir, the guild or the realm, and money that arrives later follows hourly.
- **Legacy money** — balances from before characters had their own money move to each account's heir; staff assign the rest.
- **Shared earnings** — give other plugins a common way to pay, charge and tax characters, and to respond to income, banking, and material deposits.

## Documentation

Recovered Archaeo artifacts are excluded from currency recognition, pickup, and
right-click bank deposits. A gold ingot or nugget carrying an excavation record
stays an artifact; ordinary configured currency materials continue to work.

[Project documentation](https://github.com/TF-Minecraft/Docs/blob/main/projects/DenarEconomy/README.md)

Technical documentation is maintained in [TF-Minecraft/Docs](https://github.com/TF-Minecraft/Docs).

## Tests and coverage

With Java 21 and the pinned dependencies installed (the same preparation used in
`.github/workflows/build.yml`), run:

```sh
mvn -B --no-transfer-progress clean verify
```

Tests use JUnit, MockBukkit, Mockito, real temporary files and real temporary SQLite ledgers. Surefire writes
test reports to `target/surefire-reports/`. JaCoCo reports all
production classes, without coverage exclusions, to `target/site/jacoco/`
(`index.html`, `jacoco.xml` and `jacoco.csv`); CI uploads that directory as a
`coverage-report-<run id>-<attempt>` artifact. `verify` enforces 100% line, branch and instruction
coverage. Run Maven invocations sequentially within a checkout because they share
the build directory.

The filesystem permission cases require POSIX permissions and an unprivileged
user, as provided by CI. These tests validate plugin logic and simulated Bukkit
interactions, not a live Paper server or the internals of MMOItems/TLibs.

## License

Copyright (c) 2026 TF-Minecraft contributors.

TF-Minecraft-authored material in this repository is licensed under the
[Artistic License 2.0](LICENSE). Third-party dependencies and bundled material
retain their own licenses.
