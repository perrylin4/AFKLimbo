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
            return startsWith(List.of("timeout", "warning"), args[1].toLowerCase(Locale.ROOT));
        }
        if (args[0].equalsIgnoreCase("config")
                && (args[1].equalsIgnoreCase("timeout") || args[1].equalsIgnoreCase("warning"))
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

        String targetName = target.getUsername();
        source.sendMessage(Component.text("正在将 " + targetName + " 送入 limbo...", NamedTextColor.YELLOW));

        // 异步执行：sendToLimbo -> LimboAPI spawnPlayer 内部有同步工作，可能阻塞。
        // Velocity 的玩家命令跑在玩家的连接线程上，阻塞会导致命令使用者界面卡住，
        // 所以丢到代理调度线程执行，命令立即返回。
        plugin.getServer().getScheduler().buildTask(plugin, () -> {
            if (!target.isActive()) {
                source.sendMessage(Component.text(targetName + " 已下线，取消操作", NamedTextColor.RED));
                return;
            }
            boolean success = plugin.sendToLimbo(target);
            if (success) {
                source.sendMessage(Component.text("已将 " + targetName + " 送入 AFK limbo", NamedTextColor.GREEN));
            } else {
                source.sendMessage(Component.text(targetName + " 未能送入 limbo（可能已在 limbo 或 limbo 未就绪）", NamedTextColor.RED));
            }
        }).schedule();
    }

    private void handleConfig(CommandSource source, String[] args) {
        if (args.length < 2) {
            source.sendMessage(Component.text("用法: /afklimbo config (timeout|warning) [set <秒>]", NamedTextColor.RED));
            return;
        }
        if (args[1].equalsIgnoreCase("timeout")) {
            handleTimeoutCommand(source, args);
        } else if (args[1].equalsIgnoreCase("warning")) {
            handleWarningCommand(source, args);
        } else {
            source.sendMessage(Component.text("用法: /afklimbo config (timeout|warning) [set <秒>]", NamedTextColor.RED));
        }
    }

    private void handleTimeoutCommand(CommandSource source, String[] args) {
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

    private void handleWarningCommand(CommandSource source, String[] args) {
        if (args.length == 2) {
            source.sendMessage(Component.text("当前进入 limbo 前提醒时间: " + plugin.getAfkTimeoutWarningSeconds() + " 秒", NamedTextColor.GREEN));
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
            if (seconds < 0) {
                source.sendMessage(Component.text("提醒时间不能为负数", NamedTextColor.RED));
                return;
            }
            plugin.setAfkTimeoutWarningSeconds(seconds);
            long actual = plugin.getAfkTimeoutWarningSeconds();
            source.sendMessage(Component.text("已设置提醒时间为 " + actual + " 秒" + (actual != seconds
                    ? "（受超时时间限制，已自动调整为 " + actual + " 秒）" : ""), NamedTextColor.GREEN));
            return;
        }
        source.sendMessage(Component.text("用法: /afklimbo config warning [set <秒>]", NamedTextColor.RED));
    }

    private void sendUsage(CommandSource source) {
        source.sendMessage(Component.text("AFK Limbo 命令:", NamedTextColor.GOLD));
        source.sendMessage(Component.text("  /afklimbo send <玩家名>              直接送玩家进 limbo", NamedTextColor.YELLOW));
        source.sendMessage(Component.text("  /afklimbo config timeout [set <秒>]   查看/设置挂机超时", NamedTextColor.YELLOW));
        source.sendMessage(Component.text("  /afklimbo config warning [set <秒>]   查看/设置提醒时间", NamedTextColor.YELLOW));
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
