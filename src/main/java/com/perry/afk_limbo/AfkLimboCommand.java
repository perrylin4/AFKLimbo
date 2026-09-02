package com.perry.afk_limbo;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class AfkLimboCommand implements SimpleCommand {

    private final AFKLimbo plugin;

    public AfkLimboCommand(AFKLimbo plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(SimpleCommand.Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();

        if (args.length == 0) {
            sendUsage(source);
            return;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "send" -> handleSend(source, args);
            case "config" -> handleConfig(source, args);
            default -> sendUsage(source);
        }
    }

    @Override
    public boolean hasPermission(SimpleCommand.Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length == 0) {
            return invocation.source().hasPermission("afklimbo.admin");
        }
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "send" -> invocation.source().hasPermission("afklimbo.send");
            case "config" -> invocation.source().hasPermission("afklimbo.config");
            default -> invocation.source().hasPermission("afklimbo.admin");
        };
    }

    @Override
    public List<String> suggest(SimpleCommand.Invocation invocation) {
        String[] args = invocation.arguments();

        if (args.length <= 1) {
            return startsWith(List.of("send", "config"), args.length == 1 ? args[0].toLowerCase(Locale.ROOT) : "");
        }
        if (args[0].equalsIgnoreCase("send") && args.length == 2) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            List<String> names = new ArrayList<>();
            for (Player player : plugin.getServer().getAllPlayers()) {
                if (player.getUsername().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    names.add(player.getUsername());
                }
            }
            return names;
        }
        if (args[0].equalsIgnoreCase("config") && args.length == 2) {
            return startsWith(List.of("timeout"), args[1].toLowerCase(Locale.ROOT));
        }
        if (args[0].equalsIgnoreCase("config")
                && args[1].equalsIgnoreCase("timeout")
                && args.length == 3) {
            return startsWith(List.of("set"), args[2].toLowerCase(Locale.ROOT));
        }
        return List.of();
    }

    private void handleSend(CommandSource source, String[] args) {
        if (args.length < 2) {
            source.sendMessage(Component.text("用法: /afklimbo send <玩家名>", NamedTextColor.RED));
            return;
        }
        Player target = plugin.getServer().getPlayer(args[1]).orElse(null);
        if (target == null) {
            source.sendMessage(Component.text("找不到在线玩家: " + args[1], NamedTextColor.RED));
            return;
        }
        plugin.sendToLimbo(target);
        source.sendMessage(Component.text("已将 " + target.getUsername() + " 送入 AFK limbo", NamedTextColor.GREEN));
    }

    private void handleConfig(CommandSource source, String[] args) {
        if (args.length < 2 || !args[1].equalsIgnoreCase("timeout")) {
            source.sendMessage(Component.text("用法: /afklimbo config timeout [set <秒>]", NamedTextColor.RED));
            return;
        }
        if (args.length == 2) {
            source.sendMessage(Component.text("当前挂机超时时间: " + plugin.getAfkTimeoutSeconds() + " 秒", NamedTextColor.GREEN));
            return;
        }
        if (args.length == 4 && args[2].equalsIgnoreCase("set")) {
            long seconds;
            try {
                seconds = Long.parseLong(args[3]);
            } catch (NumberFormatException e) {
                source.sendMessage(Component.text("无效的数字: " + args[3], NamedTextColor.RED));
                return;
            }
            if (seconds <= 0) {
                source.sendMessage(Component.text("超时时间必须是正整数（秒）", NamedTextColor.RED));
                return;
            }
            plugin.setAfkTimeoutSeconds(seconds);
            source.sendMessage(Component.text("已设置挂机超时时间为 " + seconds + " 秒", NamedTextColor.GREEN));
            return;
        }
        source.sendMessage(Component.text("用法: /afklimbo config timeout [set <秒>]", NamedTextColor.RED));
    }

    private void sendUsage(CommandSource source) {
        source.sendMessage(Component.text("AFK Limbo 命令:", NamedTextColor.GOLD));
        source.sendMessage(Component.text("  /afklimbo send <玩家名>           直接送玩家进 limbo", NamedTextColor.YELLOW));
        source.sendMessage(Component.text("  /afklimbo config timeout          查看挂机超时时间", NamedTextColor.YELLOW));
        source.sendMessage(Component.text("  /afklimbo config timeout set <秒>  设置挂机超时时间", NamedTextColor.YELLOW));
    }

    private List<String> startsWith(List<String> options, String prefix) {
        List<String> result = new ArrayList<>();
        for (String option : options) {
            if (option.startsWith(prefix)) {
                result.add(option);
            }
        }
        return result;
    }
}
