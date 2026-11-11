package me.pay;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.stream.Collectors;

public class PayPlugin extends JavaPlugin implements CommandExecutor, TabCompleter {

    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.builder().character('&').hexColors().build();

    private Economy econ;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            getLogger().severe("No Vault economy found! Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        econ = rsp.getProvider();
        getCommand("pay").setExecutor(this);
        getCommand("pay").setTabCompleter(this);
    }

    // Builds a message from config with %player% and %amount%
    private Component msg(String key, String def, String player, String amount) {
        String raw = getConfig().getString(key, def)
                .replace("%player%", player)
                .replace("%amount%", amount);
        return LEGACY.deserialize(raw);
    }

    private Component err(String text) {
        return Component.text(text, NamedTextColor.RED);
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only.");
            return true;
        }
        if (args.length != 2) {
            p.sendMessage(err("Usage: /pay <player> <amount>"));
            return true;
        }

        // Online or offline (must have joined before)
        OfflinePlayer target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            OfflinePlayer off = Bukkit.getOfflinePlayer(args[0]);
            if (off.hasPlayedBefore()) target = off;
        }
        if (target == null) {
            p.sendMessage(err("Player not found."));
            return true;
        }
        if (target.getUniqueId().equals(p.getUniqueId())) {
            p.sendMessage(err("You can't pay yourself."));
            return true;
        }

        double amount = parseAmount(args[1]);
        if (amount <= 0) {
            p.sendMessage(err("Invalid amount."));
            return true;
        }
        if (!econ.has(p, amount)) {
            p.sendMessage(err("You don't have enough money."));
            return true;
        }
        if (!econ.withdrawPlayer(p, amount).transactionSuccess()) {
            p.sendMessage(err("Payment failed."));
            return true;
        }
        if (!econ.depositPlayer(target, amount).transactionSuccess()) {
            econ.depositPlayer(p, amount); // refund
            p.sendMessage(err("Payment failed."));
            return true;
        }

        String amt = format(amount);
        String targetName = target.getName() != null ? target.getName() : args[0];

        // You paid someone
        p.sendMessage(msg("chat-sent", "&fYou paid %player% &a$ &f%amount%", targetName, amt));
        p.sendActionBar(msg("actionbar-sent", "&aYou paid %player% $ %amount%", targetName, amt));

        // Someone paid you (if online)
        Player online = target.getPlayer();
        if (online != null) {
            online.sendMessage(msg("chat-received", "&f%player% paid you &a$ &f%amount%", p.getName(), amt));
            online.sendActionBar(msg("actionbar-received", "&a%player% paid you $ %amount%", p.getName(), amt));
        }
        return true;
    }

    private double parseAmount(String s) {
        try {
            double v = Double.parseDouble(s.replace(",", ""));
            if (Double.isNaN(v) || Double.isInfinite(v)) return -1;
            return Math.floor(v * 100) / 100;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private String format(double v) {
        return (v == Math.floor(v)) ? String.format("%,d", (long) v) : String.format("%,.2f", v);
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        if (a.length == 1) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(n -> n.toLowerCase().startsWith(a[0].toLowerCase())).collect(Collectors.toList());
        }
        return Collections.emptyList();
    }
}
