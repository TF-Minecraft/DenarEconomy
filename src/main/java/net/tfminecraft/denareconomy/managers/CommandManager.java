package net.tfminecraft.denareconomy.managers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import net.tfminecraft.tlibs.character.CharacterInfo;
import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.tlibs.character.Characters;
import net.tfminecraft.tlibs.utils.ParseUtils;
import net.tfminecraft.denareconomy.DenarEconomy;
import net.tfminecraft.denareconomy.api.Denars;
import net.tfminecraft.denareconomy.api.Result;
import net.tfminecraft.denareconomy.data.Account;
import net.tfminecraft.denareconomy.database.BalTopEntry;
import net.tfminecraft.denareconomy.database.Database;
import net.tfminecraft.denareconomy.enums.Accounts;
import net.tfminecraft.denareconomy.item.Coin;
import net.tfminecraft.denareconomy.ledger.AccountIds;
import net.tfminecraft.denareconomy.ledger.LegacyMigration;
import net.tfminecraft.denareconomy.ledger.Ledger;
import net.tfminecraft.denareconomy.loaders.CoinLoader;
import net.tfminecraft.denareconomy.loaders.MessageLoader;
import net.tfminecraft.denareconomy.event.PlayerBankPulseEvent;

public class CommandManager implements CommandExecutor, TabCompleter {

    public String cmd1 = "deco";
    public String cmd2 = "pouch";

    private static final int HISTORY_LIMIT = 20;
    private static final DateTimeFormatter HISTORY_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase(cmd1) && args.length > 0) {
            switch (args[0].toLowerCase()) {
                case "reload":
                    handleReload(sender);
                    return true;
                case "give":
                    handleGive(sender, args);
                    return true;
                case "legacy":
                    handleLegacy(sender, args);
                    return true;
                case "history":
                    handleHistory(sender, args);
                    return true;
                case "account":
                    handleAccount(sender, args);
                    return true;
                case "move":
                    handleMove(sender, args);
                    return true;
                default:
                    break;
            }
        }

        if (!(sender instanceof Player)) {
            MessageLoader.send(sender, "general.players-only");
            return false;
        }

        Player p = (Player) sender;

        if (cmd.getName().equalsIgnoreCase(cmd2)) {
            DenarEconomy.getMoneyManager().showPouch(p);
            return true;
        }

        if (cmd.getName().equalsIgnoreCase(cmd1)) {
            if (args.length == 0) {
                sendError(p);
                return false;
            }

            switch (args[0].toLowerCase()) {
                case "bal":
                    handleBalance(p);
                    break;
                case "pay":
                    handlePay(p, args);
                    break;
                case "toitem":
                    handleToItem(p, args);
                    break;
                case "topouch":
                    DenarEconomy.getMoneyManager().toPouch(p);
                    break;
                case "deposit":
                    handleBank(p, args, true);
                    break;
                case "withdraw":
                    handleBank(p, args, false);
                    break;
                case "baltop":
                    handleBalTop(p);
                    break;
                default:
                    sendError(p);
                    break;
            }
            return true;
        }
        return false;
    }

    private void handleReload(CommandSender sender) {
        if (!canReload(sender)) {
            MessageLoader.send(sender, "errors.no-permission");
            return;
        }
        DenarEconomy.plugin.loadConfigs();
        MessageLoader.send(sender, "general.reload-ok");
    }

    private static boolean canReload(CommandSender sender) {
        return isAdmin(sender, "denareconomy.reload");
    }

    private static boolean canGive(CommandSender sender) {
        return isAdmin(sender, "denareconomy.give");
    }

    private static boolean canAdminister(CommandSender sender) {
        return isAdmin(sender, "denareconomy.admin");
    }

    /** Console always passes; a player needs operator status or the permission. */
    private static boolean isAdmin(CommandSender sender, String permission) {
        if (!(sender instanceof Player)) {
            return true;
        }
        Player player = (Player) sender;
        return player.isOp() || player.hasPermission(permission);
    }

    /** The current character of an online player, by exact name. */
    private static Optional<CharacterRef> playing(String name) {
        Player player = Bukkit.getPlayerExact(name);
        return player == null ? Optional.empty() : Denars.active(player);
    }

    /**
     * Credit the current character of an online player from console or by an operator:
     * /deco give <player> <amount> [bank|pouch]. The bank is the default.
     */
    private void handleGive(CommandSender sender, String[] args) {
        if (!canGive(sender)) {
            MessageLoader.send(sender, "errors.no-permission");
            return;
        }
        if (args.length < 3 || args.length > 4) {
            MessageLoader.send(sender, "errors.give-usage");
            return;
        }

        long cents = parseCents(args[2]);
        if (cents == 0) {
            MessageLoader.send(sender, "errors.invalid-amount");
            return;
        }

        Accounts account = args.length == 4 ? parseAccount(args[3]) : Accounts.BANK;
        if (account == null) {
            MessageLoader.send(sender, "errors.give-usage");
            return;
        }

        String name = args[1];
        Optional<CharacterRef> character = playing(name);
        if (character.isEmpty()) {
            MessageLoader.send(sender, "errors.unknown-player", "player", name);
            return;
        }

        String accountName = account.name().toLowerCase();
        String target = account == Accounts.BANK ? Denars.bank(character.get()) : Denars.pouch(character.get());
        if (Denars.transfer(MoneyManager.once("give"), "staff:give", Denars.MINT, target, cents) != Result.APPLIED) {
            MessageLoader.send(sender, "errors.give-failed", "player", name);
            return;
        }

        BigDecimal amount = MoneyManager.denars(cents);
        DenarEconomy.plugin.getLogger().info(sender.getName() + " gave " + amount + " to " + name
                + " (character " + character.get().characterId() + ") " + accountName);
        MessageLoader.send(sender, "give.sent", "amount", amount, "player", name, "account", accountName);
        MessageLoader.send(Bukkit.getPlayerExact(name), "give.received", "amount", amount, "account", accountName);
    }

    private static Accounts parseAccount(String token) {
        return switch (token.toLowerCase()) {
            case "bank" -> Accounts.BANK;
            case "pouch" -> Accounts.POUCH;
            default -> null;
        };
    }

    /**
     * /deco legacy lists money not yet moved to a character; /deco legacy assign <account>
     * <character> moves it to a character of that account.
     */
    private void handleLegacy(CommandSender sender, String[] args) {
        if (!canAdminister(sender)) {
            MessageLoader.send(sender, "errors.no-permission");
            return;
        }
        LegacyMigration migration = new LegacyMigration(DenarEconomy.getLedger(),
                DenarEconomy.plugin.getDataFolder(), DenarEconomy.plugin.getLogger());
        Map<UUID, LegacyMigration.Unclaimed> unclaimed = migration.unclaimed();
        if (args.length == 1) {
            MessageLoader.send(sender, "legacy.header", "count", unclaimed.size());
            unclaimed.forEach((account, money) -> MessageLoader.send(sender, "legacy.entry",
                    "account", account, "name", String.valueOf(Bukkit.getOfflinePlayer(account).getName()),
                    "pouch", MoneyManager.denars(money.pouch()), "bank", MoneyManager.denars(money.bank())));
            return;
        }
        UUID account;
        UUID characterId;
        try {
            if (args.length != 4 || !args[1].equalsIgnoreCase("assign")) {
                throw new IllegalArgumentException();
            }
            account = UUID.fromString(args[2]);
            characterId = UUID.fromString(args[3]);
        } catch (IllegalArgumentException ex) {
            MessageLoader.send(sender, "legacy.usage");
            return;
        }
        Optional<CharacterRef> character = Characters.info(characterId).map(CharacterInfo::ref)
                .filter(ref -> ref.ownerId().equals(account));
        if (character.isEmpty()) {
            MessageLoader.send(sender, "legacy.not-owner", "character", characterId, "account", account);
            return;
        }
        if (!unclaimed.containsKey(account)) {
            MessageLoader.send(sender, "legacy.nothing", "account", account);
            return;
        }
        Result result = migration.claim(account, character.get());
        if (result != Result.APPLIED) {
            MessageLoader.send(sender, "legacy.failed", "account", account, "result", result);
            return;
        }
        DenarEconomy.plugin.getLogger().info(sender.getName() + " assigned legacy money of " + account
                + " to character " + characterId);
        MessageLoader.send(sender, "legacy.assigned", "account", account, "character", characterId);
    }

    /** /deco history [player]: the latest postings of a current character. */
    private void handleHistory(CommandSender sender, String[] args) {
        if (args.length > 1 && !canAdminister(sender)) {
            MessageLoader.send(sender, "errors.no-permission");
            return;
        }
        if (args.length == 1 && !(sender instanceof Player)) {
            MessageLoader.send(sender, "general.players-only");
            return;
        }
        String name = args.length > 1 ? args[1] : sender.getName();
        Optional<CharacterRef> character = playing(name);
        if (character.isEmpty()) {
            MessageLoader.send(sender, "errors.unknown-player", "player", name);
            return;
        }
        String pouch = Denars.pouch(character.get());
        String bank = Denars.bank(character.get());
        List<Ledger.Posting> postings = DenarEconomy.getLedger().history(List.of(pouch, bank), HISTORY_LIMIT);
        MessageLoader.send(sender, "history.header", "player", name, "count", postings.size());
        showPostings(sender, pouch, postings);
    }

    /** One line per posting, naming the pouch or bank each change touched. */
    private static void showPostings(CommandSender sender, String pouch, List<Ledger.Posting> postings) {
        for (Ledger.Posting posting : postings) {
            List<String> changes = new ArrayList<>();
            posting.legs().forEach((account, cents) -> changes.add(MessageLoader.get("history.change",
                    "account", account.equals(pouch) ? "pouch" : "bank",
                    "amount", (cents > 0 ? "+" : "") + MoneyManager.denars(cents))));
            MessageLoader.send(sender, "history.entry", "time", HISTORY_TIME.format(Instant.ofEpochMilli(posting.created())),
                    "reason", posting.reason(), "changes", String.join(", ", changes));
        }
    }

    /** /deco account <character name|id>: both balances and the latest postings of any character. */
    private void handleAccount(CommandSender sender, String[] args) {
        if (!canAdminister(sender)) {
            MessageLoader.send(sender, "errors.no-permission");
            return;
        }
        if (args.length < 2) {
            MessageLoader.send(sender, "account.usage");
            return;
        }
        // Most character names have a space.
        String name = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        List<CharacterInfo> matches = findCharacters(name);
        if (matches.isEmpty()) {
            MessageLoader.send(sender, "account.unknown", "character", name);
            return;
        }
        if (matches.size() > 1) {
            MessageLoader.send(sender, "account.several", "count", matches.size(), "character", name);
            for (CharacterInfo match : matches) {
                MessageLoader.send(sender, "account.match", "id", match.ref().characterId(), "name", match.name(),
                        "status", match.status());
            }
            return;
        }
        CharacterInfo info = matches.getFirst();
        String pouch = Denars.pouch(info.ref());
        String bank = Denars.bank(info.ref());
        MessageLoader.send(sender, "account.header", "name", info.name(), "status", info.status(),
                "id", info.ref().characterId());
        MessageLoader.send(sender, "account.balances", "pouch", MoneyManager.denars(Denars.balance(pouch)),
                "bank", MoneyManager.denars(Denars.balance(bank)));
        showPostings(sender, pouch, DenarEconomy.getLedger().history(List.of(pouch, bank), HISTORY_LIMIT));
    }

    /**
     * A character by ID, or every character with this name: living ones from the directory and,
     * since the directory finds only living characters by name, any other with a ledger account.
     */
    private static List<CharacterInfo> findCharacters(String token) {
        try {
            return Characters.info(UUID.fromString(token)).map(List::of).orElse(List.of());
        } catch (IllegalArgumentException notAnId) {
            Map<UUID, CharacterInfo> found = new LinkedHashMap<>();
            Characters.findByName(token).forEach(info -> found.put(info.ref().characterId(), info));
            for (Ledger.Row row : DenarEconomy.getLedger().accounts("char")) {
                Characters.info(UUID.fromString(row.character()))
                        .filter(info -> info.name().equalsIgnoreCase(token))
                        .ifPresent(info -> found.putIfAbsent(info.ref().characterId(), info));
            }
            return List.copyOf(found.values());
        }
    }

    /**
     * /deco move <from account> <to account> <cents> <note>: a staff correction between any two
     * accounts, dead characters' included. It never takes a guild treasury below zero.
     */
    private void handleMove(CommandSender sender, String[] args) {
        if (!canAdminister(sender)) {
            MessageLoader.send(sender, "errors.no-permission");
            return;
        }
        if (args.length < 5) {
            MessageLoader.send(sender, "move.usage");
            return;
        }
        String from = args[1];
        String to = args[2];
        for (String account : List.of(from, to)) {
            try {
                AccountIds.parse(account);
            } catch (IllegalArgumentException ex) {
                MessageLoader.send(sender, "move.bad-account", "account", account);
                return;
            }
        }
        long cents;
        try {
            cents = Long.parseLong(args[3]);
        } catch (NumberFormatException ex) {
            cents = 0;
        }
        if (cents <= 0) {
            MessageLoader.send(sender, "errors.invalid-amount");
            return;
        }
        long held = Denars.balance(from);
        if (from.startsWith("guild:") && held < cents) {
            MessageLoader.send(sender, "move.guild-short", "account", from, "balance", MoneyManager.denars(held));
            return;
        }
        String note = String.join(" ", Arrays.copyOfRange(args, 4, args.length));
        String opId = "staff:move:" + UUID.randomUUID();
        Result result = Denars.transfer(opId, "staff:move " + note, from, to, cents);
        if (result != Result.APPLIED) {
            MessageLoader.send(sender, "move.failed", "result", result);
            return;
        }
        BigDecimal amount = MoneyManager.denars(cents);
        DenarEconomy.plugin.getLogger().info(sender.getName() + " moved " + amount + " from " + from + " to " + to
                + " (" + opId + "): " + note);
        MessageLoader.send(sender, "move.moved", "amount", amount, "from", from, "to", to);
    }

    private void handleBalTop(Player p) {
        int limit = 20;
        List<BalTopEntry> topList = Database.getTopBalances(limit);

        MessageLoader.send(p, "baltop.header", "limit", limit);
        int rank = 1;
        for (BalTopEntry entry : topList) {
            MessageLoader.send(p, "baltop.entry",
                "rank", rank++,
                "name", entry.getName(),
                "amount", String.format("%.2f", entry.getTotal()));
        }
        MessageLoader.send(p, "baltop.footer");
    }


    private void handleBalance(Player p) {
        Optional<CharacterRef> character = DenarEconomy.getMoneyManager().holder(p);
        if (character.isEmpty()) {
            return;
        }
        MessageLoader.send(p, "balance.pouch", "amount", MoneyManager.denars(Denars.balance(Denars.pouch(character.get()))));
        MessageLoader.send(p, "balance.bank", "amount", MoneyManager.denars(Denars.balance(Denars.bank(character.get()))));
    }

    private void handlePay(Player p, String[] args) {
        if (args.length < 2) {
            MessageLoader.send(p, "errors.no-amount");
            return;
        }

        long cents = parseCents(args[1]);

        if (cents == 0) {
            MessageLoader.send(p, "errors.invalid-amount");
            return;
        }

        DenarEconomy.getMoneyManager().pay(p, cents);
    }

    private void handleToItem(Player p, String[] args) {
        if (args.length < 2) {
            MessageLoader.send(p, "errors.no-amount");
            return;
        }

        Double amount = ParseUtils.parseDouble(args[1]);

        if (!ParseUtils.isPositive(amount) || !Double.isFinite(amount)) {
            MessageLoader.send(p, "errors.invalid-amount");
            return;
        }

        List<ItemStack> items;
        long cents;

        if (args.length >= 3) {
            Coin coin = resolveToItemCoin(args[2]);
            if (coin == null) {
                MessageLoader.send(p, "errors.unknown-coin");
                return;
            }
            if (amount != Math.floor(amount)) {
                MessageLoader.send(p, "errors.invalid-count");
                return;
            }
            long count = Math.round(amount);
            cents = Math.round(count * coin.getValue() * 100.0);
            items = DenarEconomy.getMoneyManager().coinItems(coin, count);
        } else {
            cents = parseCents(args[1]);
            if (cents == 0) {
                MessageLoader.send(p, "errors.invalid-amount");
                return;
            }
            // A one-cent floor requires an exact plan, unlike world drops with a custom total value.
            items = DenarEconomy.getMoneyManager().amountToItems(cents / 100.0, 0.0, 0.01);
        }

        if (items.isEmpty()) {
            MessageLoader.send(p, "errors.coins-unavailable");
            return;
        }

        Optional<CharacterRef> character = DenarEconomy.getMoneyManager().holder(p);
        if (character.isEmpty()) {
            return;
        }
        Result result = Denars.transfer(MoneyManager.once("toitem"), "toitem", Denars.pouch(character.get()),
                Denars.MINT, cents);
        if (result != Result.APPLIED) {
            MoneyManager.refusal(p, result, "errors.not-enough-pouch");
            return;
        }
        giveItems(p, items);
    }

    private static Coin resolveToItemCoin(String token) {
        Coin byId = CoinLoader.getByString(token);
        if (byId != null) {
            return byId.canWithdraw() ? byId : null;
        }
        Double value = ParseUtils.parseDouble(token);
        if (!ParseUtils.isPositive(value)) {
            return null;
        }
        return CoinLoader.getWithdrawableByValue(value);
    }

    private static void giveItems(Player p, List<ItemStack> items) {
        for (ItemStack i : items) {
            for (ItemStack remaining : p.getInventory().addItem(i).values()) {
                p.getWorld().dropItem(p.getLocation(), remaining);
            }
        }
    }

    /** Whole cents of a positive amount with at most two decimals, or 0 when the token is not one. */
    static long parseCents(String token) {
        try {
            BigDecimal amount = new BigDecimal(token);
            return Account.isValidTransferAmount(amount) ? amount.movePointRight(2).longValueExact() : 0;
        } catch (NumberFormatException | ArithmeticException ex) {
            return 0;
        }
    }

    /** /deco deposit and /deco withdraw: move money between the current character's pouch and bank. */
    private void handleBank(Player p, String[] args, boolean deposit) {
        Optional<CharacterRef> character = DenarEconomy.getMoneyManager().holder(p);
        if (character.isEmpty()) {
            return;
        }
        PlayerBankPulseEvent event = new PlayerBankPulseEvent(p, character.get());
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) return;

        if (args.length < 2) {
            MessageLoader.send(p, "errors.no-amount");
            return;
        }

        long cents = parseCents(args[1]);

        if (cents == 0) {
            MessageLoader.send(p, "errors.invalid-amount");
            return;
        }

        String pouch = Denars.pouch(character.get());
        String bank = Denars.bank(character.get());
        String reason = deposit ? "deposit" : "withdraw";
        Result result = Denars.transfer(MoneyManager.once(reason), reason, deposit ? pouch : bank,
                deposit ? bank : pouch, cents);
        if (result != Result.APPLIED) {
            MoneyManager.refusal(p, result, deposit ? "errors.not-enough-pouch" : "errors.not-enough-bank");
            return;
        }

        MessageLoader.send(p, "bank.header");
        MessageLoader.send(p, "bank.action", "action", MessageLoader.get(deposit ? "bank.deposited" : "bank.withdrew"),
                "amount", MoneyManager.denars(cents));
        MessageLoader.send(p, "bank.new-bank", "amount", MoneyManager.denars(Denars.balance(bank)));
        MessageLoader.send(p, "bank.new-pouch", "amount", MoneyManager.denars(Denars.balance(pouch)));
        MessageLoader.send(p, "bank.footer");
        p.playSound(p, Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 1f);
    }

    private static List<String> giveCompletions(int position) {
        return switch (position) {
            case 2 -> List.of("<player>");
            case 3 -> List.of("<amount>");
            case 4 -> List.of("bank", "pouch");
            default -> List.of();
        };
    }

    private void sendError(Player p) {
        MessageLoader.send(p, "general.unknown-subcommand");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        if (cmd.getName().equalsIgnoreCase(cmd1)) {
            if (args.length == 1) {
                completions.add("bal");
                completions.add("pay");
                completions.add("toitem");
                completions.add("topouch");
                completions.add("deposit");
                completions.add("withdraw");
                completions.add("baltop");
                completions.add("history");
                if (canGive(sender)) {
                    completions.add("give");
                }
                if (canReload(sender)) {
                    completions.add("reload");
                }
                if (canAdminister(sender)) {
                    completions.add("legacy");
                    completions.add("account");
                    completions.add("move");
                }
            } else if (args.length > 1 && args[0].equalsIgnoreCase("give")) {
                if (canGive(sender)) {
                    completions.addAll(giveCompletions(args.length));
                }
            } else if (args.length == 2 && args[0].equalsIgnoreCase("legacy")) {
                if (canAdminister(sender)) {
                    completions.add("assign");
                }
            } else if (args.length == 2) {
                if (args[0].equalsIgnoreCase("pay") || args[0].equalsIgnoreCase("toitem") ||
                    args[0].equalsIgnoreCase("deposit") || args[0].equalsIgnoreCase("withdraw")) {
                    completions.add("<amount>");
                }
            } else if (args.length == 3 && args[0].equalsIgnoreCase("toitem")) {
                for (Coin coin : CoinLoader.get()) {
                    if (coin.canWithdraw()) {
                        completions.add(coin.getId());
                    }
                }
            }
        } else if (cmd.getName().equalsIgnoreCase(cmd2)) {
            // "pouch" has no subcommands, so nothing to suggest
            completions = null;
        }
        if (completions == null) return null;

        // Filter completions based on current input (for better experience)
        return completions.stream()
                .filter(c -> c.toLowerCase().startsWith(args[args.length - 1].toLowerCase()))
                .collect(Collectors.toList());
    }
}
