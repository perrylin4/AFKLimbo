package com.perry.afk_limbo;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.User;
import com.google.inject.Inject;
import com.moandjiezana.toml.Toml;
import com.moandjiezana.toml.TomlWriter;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.ServerInfo;
import net.elytrium.limboapi.api.Limbo;
import net.elytrium.limboapi.api.LimboFactory;
import net.elytrium.limboapi.api.LimboSessionHandler;
import net.elytrium.limboapi.api.chunk.Dimension;
import net.elytrium.limboapi.api.chunk.VirtualWorld;
import net.elytrium.limboapi.api.command.LimboCommandMeta;
import net.elytrium.limboapi.api.file.BuiltInWorldFileType;
import net.elytrium.limboapi.api.file.WorldFile;
import net.elytrium.limboapi.api.material.Block;
import net.elytrium.limboapi.api.player.GameMode;
import net.elytrium.limboapi.api.player.LimboPlayer;
import net.elytrium.limboapi.api.protocol.packets.data.AbilityFlags;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Plugin(
        id = "afk_limbo",
        name = "AFK Limbo",
        version = "1.0-SNAPSHOT",
        description = "A plugin to throw afk player into limbo",
        authors = {"perry_lin"},
        dependencies = {
                @Dependency(id = "packetevents"),
                @Dependency(id = "limboapi")
        }
)
public class AFKLimbo {

    private final Logger logger;
    private final ProxyServer server;
    private final Path dataDirectory;

    /** 玩家 UUID -> 最后活动时间戳（ms） */
    private final Map<UUID, Long> lastActivity = new ConcurrentHashMap<>();

    private Toml config;
    private Limbo afkLimbo;
    private final Map<UUID, LimboPlayer> limboPlayers = new ConcurrentHashMap<>();

    /** 当前挂机超时时间（秒），可由命令修改并写回配置 */
    private volatile long afkTimeoutSeconds = 300;

    @Inject
    public AFKLimbo(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.logger = logger;
        this.server = server;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        loadConfig();
        registerPacketListener();
        startAfkCheckTask();
        registerCommands();
        logger.info("AFK Limbo initialized!");
        server.getScheduler().buildTask(this, this::createAfkLimbo)
                .delay(10, TimeUnit.SECONDS).schedule();
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        lastActivity.clear();
        if (afkLimbo != null) afkLimbo.dispose();
        logger.info("AFK Limbo has been shut down!");
    }

    private void registerCommands() {
        CommandManager commandManager = server.getCommandManager();
        CommandMeta meta = commandManager.metaBuilder("afklimbo")
                .aliases("afk")
                .plugin(this)
                .build();
        commandManager.register(meta, new AfkLimboCommand(this));
        logger.info("已注册命令 /afklimbo");
    }

    // ---------- 配置 ----------

    private void loadConfig() {
        Path file = dataDirectory.resolve("config.toml");
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(dataDirectory);
                Files.writeString(file, """
                        # AFK 超过多少秒后送入 limbo
                        afk-timeout-seconds = 300

                        # 白名单：只有这些服务器的玩家才会被送入 limbo
                        enabled-servers = ["lobby"]

                        # 送入 limbo 时导入的世界文件（放在 插件数据目录/worlds/ 下）
                        # 支持 .schem / .schematic / .nbt；留空则使用空世界
                        limbo-world-file = ""

                        # limbo 出生点坐标与朝向
                        limbo-spawn-x = 0.0
                        limbo-spawn-y = 64.0
                        limbo-spawn-z = 0.0
                        limbo-spawn-yaw = 0.0
                        limbo-spawn-pitch = 0.0
                        """);
            }
            config = new Toml().read(file.toFile());
            Long timeout = config.getLong("afk-timeout-seconds", 300L);
            afkTimeoutSeconds = timeout != null && timeout > 0 ? timeout : 300L;
        } catch (Exception e) {
            logger.error("Failed to load config", e);
            config = new Toml();
        }
    }

    /** 玩家当前所在服务器是否启用了 AFK 送入 limbo（白名单判断） */
    private boolean isAfkLimboEnabled(Player player) {
        return player.getCurrentServer()
                .map(ServerConnection::getServerInfo)
                .map(ServerInfo::getName)
                .map(this::isInWhitelist)
                .orElse(false);
    }

    private boolean isInWhitelist(String serverName) {
        List<String> enabledServers = config.getList("enabled-servers");
        return enabledServers != null && enabledServers.contains(serverName);
    }

    /** 读取 double 配置，缺失或类型错误时返回默认值 */
    private double getDouble(String key, double defaultValue) {
        Double value = config.getDouble(key, defaultValue);
        return value != null ? value : defaultValue;
    }

    // ---------- Limbo ----------

    private void createAfkLimbo() {
        LimboFactory factory = (LimboFactory) server.getPluginManager()
                .getPlugin("limboapi")
                .flatMap(PluginContainer::getInstance)
                .orElseThrow(() -> new IllegalStateException("LimboAPI 未安装"));

        double spawnX = getDouble("limbo-spawn-x", 0.0);
        double spawnY = getDouble("limbo-spawn-y", 64.0);
        double spawnZ = getDouble("limbo-spawn-z", 0.0);
        float spawnYaw = (float) getDouble("limbo-spawn-yaw", 0.0);
        float spawnPitch = (float) getDouble("limbo-spawn-pitch", 0.0);
        VirtualWorld world = factory.createVirtualWorld(
                Dimension.OVERWORLD, spawnX, spawnY, spawnZ, spawnYaw, spawnPitch);

        String worldFile = config.getString("limbo-world-file", "");
        boolean importedWorld = worldFile != null && !worldFile.isBlank();
        if (importedWorld) importedWorld = loadWorldFile(factory, world, worldFile);
        if (!importedWorld) generatedFallbackLimbo(world, factory);

        afkLimbo = factory.createLimbo(world);
        afkLimbo.setName("afk_limbo");
        afkLimbo.setViewDistance(3);
        afkLimbo.setSimulationDistance(2);
        afkLimbo.setShouldRespawn(true);
        afkLimbo.registerCommand(new LimboCommandMeta(List.of("hub")));
        afkLimbo.setShouldRejoin(true);
        afkLimbo.setGameMode(GameMode.ADVENTURE);
    }

    /** 把插件数据目录 worlds/ 下的世界文件导入到 limbo 的虚拟世界 */
    private boolean loadWorldFile(LimboFactory factory, VirtualWorld world, String fileName) {
        BuiltInWorldFileType type = worldFileType(fileName);
        if (type == null) {
            logger.warn("不支持的世界文件类型: {}（支持 .schem / .schematic / .nbt），使用空世界", fileName);
            return false;
        }

        Path file = dataDirectory.resolve("worlds").resolve(fileName);
        if (!Files.exists(file)) {
            logger.warn("世界文件不存在: {}，使用空世界", file);
            return false;
        }

        try {
            WorldFile opened = factory.openWorldFile(type, file);
            opened.toWorld(factory, world, 0, 0, 0);
            logger.info("已导入世界文件 {} 到 AFK limbo", fileName);
        } catch (IOException e) {
            logger.error("导入世界文件 {} 失败，使用空世界", fileName, e);
            return false;
        }
        return true;
    }

    List<BlockPos> dirs = List.of(
            new BlockPos(0, -5, 0),
            new BlockPos(1, -5, 0),
            new BlockPos(-1, -5, 0),
            new BlockPos(0, -5, 1),
            new BlockPos(1, -5, 1),
            new BlockPos(-1, -5, 1),
            new BlockPos(0, -5, -1),
            new BlockPos(1, -5, -1),
            new BlockPos(-1, -5, -1)
    );

    private void generatedFallbackLimbo(VirtualWorld world, LimboFactory factory) {
        int x = Math.toIntExact(Math.round(getDouble("limbo-spawn-x", 0.0)));
        int y = Math.toIntExact(Math.round(getDouble("limbo-spawn-y", 64.0)));
        int z = Math.toIntExact(Math.round(getDouble("limbo-spawn-z", 0.0)));
        dirs.forEach(pos -> world.setBlock(
                pos.x + x,
                pos.y + y,
                pos.z + z,
                factory.createSimpleBlock(Block.GLASS)
        ));
    }

    private BuiltInWorldFileType worldFileType(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".schem")) {
            return BuiltInWorldFileType.SCHEMATIC;
        }
        if (lower.endsWith(".schematic")) {
            return BuiltInWorldFileType.WORLDEDIT_SCHEM;
        }
        if (lower.endsWith(".nbt")) {
            return BuiltInWorldFileType.STRUCTURE;
        }
        return null;
    }

    public void sendToLimbo(Player player) {
        afkLimbo.spawnPlayer(player, new LimboSessionHandler() {
            @Override
            public void onChat(String message) {
                LimboPlayer limboPlayer = limboPlayers.get(player.getUniqueId());
                if (message.startsWith("/hub")) {
                    if (limboPlayer == null) {
                        logger.warn("Unable to find player in limbo: {}!", player.getUsername());
                        return;
                    }
                    limboPlayer.disconnect();
                }
            }
            @Override
            public void onSpawn(Limbo server, LimboPlayer player) {
                player.sendAbilities(
                        AbilityFlags.ALLOW_FLYING + AbilityFlags.FLYING + AbilityFlags.INVULNERABLE + AbilityFlags.CREATIVE_MODE,
                        0.1f,
                        1f
                );
                limboPlayers.put(player.getProxyPlayer().getUniqueId(), player);
            }
            @Override
            public void onDisconnect() {
                limboPlayers.remove(player.getUniqueId());
            }
        });
        logger.info("{} 已送入 AFK limbo", player.getUsername());
    }

    public ProxyServer getServer() {
        return server;
    }

    public long getAfkTimeoutSeconds() {
        return afkTimeoutSeconds;
    }

    /** 修改挂机超时时间并写回 config.toml（同时保留其他所有配置项） */
    public void setAfkTimeoutSeconds(long seconds) {
        afkTimeoutSeconds = seconds;
        saveConfig();
    }

    private void saveConfig() {
        try {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("afk-timeout-seconds", afkTimeoutSeconds);
            List<String> servers = config.getList("enabled-servers");
            values.put("enabled-servers", servers != null ? servers : List.of());
            values.put("limbo-world-file", config.getString("limbo-world-file", ""));
            values.put("limbo-spawn-x", config.getDouble("limbo-spawn-x", 0.0));
            values.put("limbo-spawn-y", config.getDouble("limbo-spawn-y", 64.0));
            values.put("limbo-spawn-z", config.getDouble("limbo-spawn-z", 0.0));
            values.put("limbo-spawn-yaw", config.getDouble("limbo-spawn-yaw", 0.0));
            values.put("limbo-spawn-pitch", config.getDouble("limbo-spawn-pitch", 0.0));
            new TomlWriter().write(values, dataDirectory.resolve("config.toml").toFile());
            logger.info("AFK 超时时间已保存为 {} 秒", afkTimeoutSeconds);
        } catch (IOException e) {
            logger.error("保存 config.toml 失败", e);
        }
    }

    // ---------- 活动检测（PacketEvents 监听数据包） ----------

    private void registerPacketListener() {
        PacketEvents.getAPI().getEventManager().registerListener(new PacketListener() {
            @Override
            public void onPacketReceive(@NotNull PacketReceiveEvent event) {
                if (shouldResetTimer(event.getPacketType())) {
                    User user = event.getUser();
                    lastActivity.put(user.getUUID(), System.currentTimeMillis());
                }
            }
        }, PacketListenerPriority.NORMAL);
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        lastActivity.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        lastActivity.remove(event.getPlayer().getUniqueId());
    }

    private boolean shouldResetTimer(PacketTypeCommon type) {
        return type == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION
                || type == PacketType.Play.Client.PLAYER_ROTATION
                || type == PacketType.Play.Client.CHAT_MESSAGE
                || type == PacketType.Play.Client.CHAT_COMMAND
                || type == PacketType.Play.Client.ATTACK
                || type == PacketType.Play.Client.INTERACT_ENTITY
                || type == PacketType.Play.Client.USE_ITEM
                || type == PacketType.Play.Client.HELD_ITEM_CHANGE
                || type == PacketType.Play.Client.PLAYER_INPUT
                || type == PacketType.Play.Client.ANIMATION;
    }

    // ---------- 定时检查 ----------

    private void startAfkCheckTask() {
        server.getScheduler().buildTask(this, () -> {
            long now = System.currentTimeMillis();
            long timeoutMs = afkTimeoutSeconds * 1000L;

            for (Map.Entry<UUID, Long> entry : lastActivity.entrySet()) {
                Player player = server.getPlayer(entry.getKey()).orElse(null);
                if (player == null) {
                    lastActivity.remove(entry.getKey());
                    continue;
                }
                if (now - entry.getValue() < timeoutMs) {
                    continue;
                }
                // 超时 → 白名单判断（此刻玩家还在原服，getCurrentServer() 有效）
                if (isAfkLimboEnabled(player)) {
                    sendToLimbo(player);
                    lastActivity.put(entry.getKey(), now); // 防止下一轮重复送入
                }
            }
        }).repeat(10, TimeUnit.SECONDS).schedule();
    }

    private record BlockPos(int  x, int y, int z){}
}
