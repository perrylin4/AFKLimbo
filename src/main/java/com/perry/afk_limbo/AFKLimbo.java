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
import com.velocitypowered.api.scheduler.ScheduledTask;
import net.elytrium.limboapi.api.Limbo;
import net.elytrium.limboapi.api.LimboFactory;
import net.elytrium.limboapi.api.LimboSessionHandler;
import net.elytrium.limboapi.api.chunk.Dimension;
import net.elytrium.limboapi.api.chunk.VirtualChunk;
import net.elytrium.limboapi.api.chunk.VirtualWorld;
import net.elytrium.limboapi.api.command.LimboCommandMeta;
import net.elytrium.limboapi.api.file.BuiltInWorldFileType;
import net.elytrium.limboapi.api.file.WorldFile;
import net.elytrium.limboapi.api.material.Block;
import net.elytrium.limboapi.api.player.GameMode;
import net.elytrium.limboapi.api.player.LimboPlayer;
import net.elytrium.limboapi.api.protocol.packets.data.AbilityFlags;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
    private Limbo afkLimbo = null;
    private ScheduledTask createLimboTask = null;
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
        startTasks();
        registerCommands();
        logger.info("AFK Limbo initialized!");
        createLimboTask = server.getScheduler().buildTask(this, this::createAfkLimbo)
                .delay(10, TimeUnit.SECONDS).schedule();
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        lastActivity.clear();
        if (afkLimbo != null) afkLimbo.dispose();
        if (createLimboTask != null) {
            try {
                createLimboTask.cancel();
            } catch (Exception ignored) {}
        }
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

                        # 世界文件导入偏移（把建筑平移到出生点附近，单位：格）
                        # 如果 schematic 里的建筑不在原点附近，用负偏移把它挪回来
                        limbo-world-offset-x = 0
                        limbo-world-offset-y = 0
                        limbo-world-offset-z = 0

                        # limbo 出生点坐标与朝向
                        limbo-spawn-x = 0.0
                        limbo-spawn-y = 64.0
                        limbo-spawn-z = 0.0
                        limbo-spawn-yaw = 0.0
                        limbo-spawn-pitch = 0.0
                        
                        # limbo 视距和模拟距离
                        limbo-view-distance = 2
                        limbo-simulate-distance = 5
                        
                        # limbo 最低y值与世界高度(必须为16的整数倍,且与LimboAPI设置一致)
                        limbo-min-y = -64
                        limbo-height = 384
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
                .orElse(false) && afkTimeoutSeconds > 0;
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

    /** 读取 int 配置，缺失或类型错误时返回默认值 */
    private int getInt(String key, int defaultValue) {
        Long value = config.getLong(key, (long) defaultValue);
        return value != null ? value.intValue() : defaultValue;
    }

    // ---------- Limbo ----------

    private void createAfkLimbo() {
        long startTime = System.currentTimeMillis();
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
        logger.info("generating limbo world...");
        if (importedWorld) importedWorld = loadWorldFile(factory, world, worldFile);
        if (!importedWorld) generatedFallbackLimbo(world, factory);

        logger.info("creating limbo server...");
        afkLimbo = factory.createLimbo(world)
                .setName("afk_limbo")
                .setViewDistance(getInt("limbo-view-distance", 2))
                .setSimulationDistance(getInt("limbo-simulate-distance", 5))
                .setShouldRespawn(true)
                .registerCommand(new LimboCommandMeta(List.of("hub")))
                .setShouldRejoin(true)
                .setGameMode(GameMode.ADVENTURE)
                .build();
        logger.info("limbo world generated! duration: {}ms", System.currentTimeMillis() - startTime);
    }

    /** 把插件数据目录 worlds/ 下的世界文件导入到 limbo 的虚拟世界 */
    private boolean loadWorldFile(LimboFactory factory, VirtualWorld world, String fileName) {
        Path file = dataDirectory.resolve("worlds").resolve(fileName);
        if (!Files.exists(file)) {
            logger.warn("世界文件不存在: {}，使用空世界", file);
            return false;
        }

        int offsetX = getInt("limbo-world-offset-x", 0);
        int offsetY = getInt("limbo-world-offset-y", 0);
        int offsetZ = getInt("limbo-world-offset-z", 0);

        try {
            logger.info("Loading world file...");
            WorldFile opened = factory.openWorldFile(BuiltInWorldFileType.WORLDEDIT_SCHEM, file);
            logger.info("World file loaded! Converting to world");
            opened.toWorld(factory, world, offsetX, offsetY, offsetZ);
        } catch (IOException e) {
            logger.error("导入世界文件 {} 失败，使用空世界", fileName, e);
            return false;
        }

        try {
            // ---- 临时调试：输出所有区块及是否为空 ----
            final int world_min_y = getInt("limbo-min-y", -64);
            final int world_max_y = getInt("limbo-height", 384) + world_min_y;
            List<VirtualChunk> chunks = world.getChunks();
            logger.info("世界共有 {} 个区块:", chunks.size());
            final AtomicInteger total = new AtomicInteger(0);
            final AtomicInteger non_empty = new AtomicInteger(0);
            final AtomicInteger x_min = new AtomicInteger(Integer.MAX_VALUE);
            final AtomicInteger x_max = new AtomicInteger(Integer.MIN_VALUE);
            final AtomicInteger y_min = new AtomicInteger(Integer.MAX_VALUE);
            final AtomicInteger y_max = new AtomicInteger(Integer.MIN_VALUE);
            final AtomicInteger z_min = new AtomicInteger(Integer.MAX_VALUE);
            final AtomicInteger z_max = new AtomicInteger(Integer.MIN_VALUE);
            chunks.parallelStream().forEach(chunk -> {
                int perChunk = 0;
                if (chunk.getPosX() < x_min.get()) {
                    x_min.set(chunk.getPosX());
                }
                if (chunk.getPosX() > x_max.get()) {
                    x_max.set(chunk.getPosX());
                }
                if (chunk.getPosZ() < z_min.get()) {
                    z_min.set(chunk.getPosZ());
                }
                if (chunk.getPosZ() > z_max.get()) {
                    z_max.set(chunk.getPosZ());
                }
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        for (int y = world_min_y; y < world_max_y; y++) {
                            if (!chunk.getBlock(x, y, z).isAir()) {
                                if (y > y_max.get()) {
                                    y_max.set(y);
                                }
                                if (y < y_min.get()) {
                                    y_min.set(y);
                                }
                                perChunk++;
                            }
                        }
                    }
                }
                total.addAndGet(perChunk);
                if (perChunk > 0) non_empty.incrementAndGet();
            });
            logger.info(
                    "合计 {} 个非空气方块, {} 个非空区块, x在区域[{}, {}], z在区域[{}, {}], 世界方块所在y区域[{}. {}]",
                    total.get(), non_empty.get(),
                    x_min.get(), x_max.get(), z_min.get(), z_max.get(), y_min.get(), y_max.get()
            );
        } catch (Exception ignored) {}
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

    public boolean sendToLimbo(Player player) {
        // prevent spam
        lastActivity.put(player.getUniqueId(), System.currentTimeMillis());
        if (afkLimbo == null) {
            logger.warn("trying to send player {} to limbo while limbo is not ready!", player.getUsername());
            return false;
        }
        afkLimbo.spawnPlayer(player, new LimboSessionHandler() {
            @Override
            public void onChat(String message) {
                LimboPlayer limboPlayer = limboPlayers.get(player.getUniqueId());
                if (message.startsWith("/hub")) {
                    if (limboPlayer == null) {
                        logger.warn("Unable to find player in limbo: {}!", player.getUsername());
                        return;
                    }
                    player.clearTitle();
                    limboPlayer.disconnect();
                }
            }
            @Override
            public void onSpawn(Limbo server, LimboPlayer player) {
                player.sendAbilities(
                        AbilityFlags.INVULNERABLE,
                        0.1f,
                        1f
                );
                limboPlayers.put(player.getProxyPlayer().getUniqueId(), player);
                player.getProxyPlayer().sendPlayerListHeader(getTabListMessage(player));
                player.getProxyPlayer().showTitle(Title.title(
                        Component.empty(),
                        getTitleMessage(player),
                        Title.Times.times(Duration.ZERO, Duration.ofDays(7), Duration.ZERO)
                ));
            }

            private @NonNull Component getTabListMessage(LimboPlayer player) {
                boolean chinese = player.getProxyPlayer().getEffectiveLocale() == Locale.CHINA;
                Component message;
                if (chinese) {
                    message = Component.text("这是你的神秘梦境")
                            .appendNewline().append(Component.text("输入/hub醒过来"));
                } else {
                    message = Component.text("You have fallen asleep")
                            .appendNewline().append(Component.text("type '/hub' in chat to wake up"));
                }
                return message;
            }

            @Override
            public void onDisconnect() {
                limboPlayers.remove(player.getUniqueId());
            }
        });
        logger.info("{} 已送入 AFK limbo", player.getUsername());
        return true;
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
            values.put("limbo-world-offset-x", getInt("limbo-world-offset-x", 0));
            values.put("limbo-world-offset-y", getInt("limbo-world-offset-y", 0));
            values.put("limbo-world-offset-z", getInt("limbo-world-offset-z", 0));
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

    private void startTasks() {
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

    private @NonNull Component getTitleMessage(LimboPlayer player) {
        boolean chinese = player.getProxyPlayer().getEffectiveLocale() == Locale.CHINA;
        Component message;
        if (chinese) {
            message = Component.text("输入/hub返回大厅");
        } else {
            message = Component.text("type '/hub' to return to lobby");
        }
        return message;
    }

    private record BlockPos(int x, int y, int z){}
}
