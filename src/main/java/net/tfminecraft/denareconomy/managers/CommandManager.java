package net.tfminecraft.denareconomy.managers;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import net.tfminecraft.tlibs.utils.ParseUtils;
import net.tfminecraft.denareconomy.DenarEconomy;
import net.tfminecraft.denareconomy.data.Account;
import net.tfminecraft.denareconomy.data.PlayerData;
import net.tfminecraft.denareconomy.database.BalTopEntry;
import net.tfminecraft.denareconomy.database.Database;
import net.tfminecraft.denareconomy.item.Coin;
import net.tfminecraft.denareconomy.loaders.CoinLoader;
import net.tfminecraft.denareconomy.loaders.MessageLoader;
import net.tfminecraft.denareconomy.event.PlayerBankPulseEvent;
import net.tfminecraft.denareconomy.event.PlayerDepositMaterialsEvent;

public class CommandManager implements CommandExecutor, TabCompleter {

    public String cmd1 = "deco";
    public String cmd2 = "pouch";


    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase(cmd1) && args.length > 0
                && args[0].equalsIgnoreCase("reload")) {
            handleReload(sender);
            return true;
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
                case "deposit":
                    handleDeposit(p, args);
                    break;
                case "withdraw":
                    handleWithdraw(p, args);
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
        if (!(sender instanceof Player)) {
            return true;
        }
        Player player = (Player) sender;
        return player.isOp() || player.hasPermission("denareconomy.reload");
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
        PlayerData pd = DenarEconomy.getPlayerManager().get(p);
        MessageLoader.send(p, "balance.pouch", "amount", pd.getPouch().getBal());
        MessageLoader.send(p, "balance.bank", "amount", pd.getBank().getBal());
    }

    private void handlePay(Player p, String[] args) {
        if (args.length < 2) {
            MessageLoader.send(p, "errors.no-amount");
            return;
        }

        Double amount = ParseUtils.parseDouble(args[1]);

        if (!ParseUtils.isPositive(amount)) {
            MessageLoader.send(p, "errors.invalid-amount");
            return;
        }

        DenarEconomy.getMoneyManager().pay(p, amount);
    }

    private void handleToItem(Player p, String[] args) {
        if (args.length < 2) {
            MessageLoader.send(p, "errors.no-amount");
            return;
        }

        Double amount = ParseUtils.parseDouble(args[1]);

        if (!ParseUtils.isPositive(amount)) {
            MessageLoader.send(p, "errors.invalid-amount");
            return;
        }

        Account pouch = DenarEconomy.getPlayerManager().get(p).getPouch();
        List<ItemStack> items;
        double cost;

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
            cost = count * coin.getValue();
            items = DenarEconomy.getMoneyManager().coinItems(coin, count);
        } else {
            cost = amount;
            items = DenarEconomy.getMoneyManager().amountToItems(amount);
        }

        if (pouch.getBal() < cost) {
            MessageLoader.send(p, "errors.not-enough-pouch");
            return;
        }

        pouch.change(-cost);
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
            if (p.getInventory().firstEmpty() == -1) {
                p.getWorld().dropItem(p.getLocation(), i);
            } else {
                p.getInventory().addItem(i);
            }
        }
    }

    /** Parses the original command token without rounding fractional cents through double. */
    static BigDecimal parseBankAmount(String token) {
        if (token == null) return null;
        try {
            BigDecimal amount = new BigDecimal(token);
            return Account.isValidTransferAmount(amount) ? amount.setScale(2) : null;
        } catch (NumberFormatException | ArithmeticException ex) {
            return null;
        }
    }

    private void handleDeposit(Player p, String[] args) {
        PlayerBankPulseEvent event = new PlayerBankPulseEvent(p);
		Bukkit.getPluginManager().callEvent(event);
        if(event.isCancelled()) return;

        if (args.length < 2) {
            MessageLoader.send(p, "errors.no-amount");
            return;
        }

        BigDecimal amount = parseBankAmount(args[1]);

        if (amount == null) {
            MessageLoader.send(p, "errors.invalid-amount");
            return;
        }

        PlayerData pd = DenarEconomy.getPlayerManager().get(p);
        if (!MoneyManager.transfer(pd.getPouch(), pd.getBank(), amount)) {
            MessageLoader.send(p, "errors.not-enough-pouch");
            return;
        }

        sendBankReport(p, MessageLoader.get("bank.deposited"), amount, pd);
    }

    private void handleWithdraw(Player p, String[] args) {
        PlayerBankPulseEvent event = new PlayerBankPulseEvent(p);
		Bukkit.getPluginManager().callEvent(event);
        if(event.isCancelled()) return;

        if (args.length < 2) {
            MessageLoader.send(p, "errors.no-amount");
            return;
        }

        BigDecimal amount = parseBankAmount(args[1]);

        if (amount == null) {
            MessageLoader.send(p, "errors.invalid-amount");
            return;
        }

        PlayerData pd = DenarEconomy.getPlayerManager().get(p);
        if (!MoneyManager.transfer(pd.getBank(), pd.getPouch(), amount)) {
            MessageLoader.send(p, "errors.not-enough-bank");
            return;
        }

        sendBankReport(p, MessageLoader.get("bank.withdrew"), amount, pd);
    }

    private void sendBankReport(Player p, String action, BigDecimal amount, PlayerData pd) {
        MessageLoader.send(p, "bank.header");
        MessageLoader.send(p, "bank.action", "action", action, "amount", amount);
        MessageLoader.send(p, "bank.new-bank", "amount", pd.getBank().getBal());
        MessageLoader.send(p, "bank.new-pouch", "amount", pd.getPouch().getBal());
        MessageLoader.send(p, "bank.footer");
        p.playSound(p, Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 1f);
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
                completions.add("deposit");
                completions.add("withdraw");
                completions.add("baltop");
                if (canReload(sender)) {
                    completions.add("reload");
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
