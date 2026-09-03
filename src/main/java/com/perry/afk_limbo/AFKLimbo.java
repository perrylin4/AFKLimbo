package com.perry.afk_limbo;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.sound.SoundCategory;
import com.github.retrooper.packetevents.protocol.sound.Sounds;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerPosition;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerPositionAndRotation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerPositionAndLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSoundEffect;
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
import net.kyori.adventure.text.minimessage.MiniMessage;
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
    private final Map<UUID, Vector3d> lastPositions = new ConcurrentHashMap<>();

    /** 当前挂机超时时间（秒），可由命令修改并写回配置 */
    private volatile long afkTimeoutSeconds = 300;

    /** 进入 limbo 前的提醒时间（秒） */
    private volatile long afkTimeoutWarningSeconds = 60;

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
        lastPositions.clear();
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
                        # AFK 即将进入limbo前多少秒提醒玩家
                        afk-timeout-warning-seconds = 60

                        # 白名单：只有这些服务器的玩家才会被送入 limbo
                        enabled-servers = ["lobby"]

                        # 送入 limbo 时导入的世界文件（放在 插件数据目录/worlds/ 下）
                        # 仅支持 .schem；留空则使用空世界
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
                        # 如果你不确定你的schem文件的y的范围，建议先设置成
                        # min-y = -2032, height = 4064，然后从后台看你建筑的实际范围
                        limbo-min-y = -64
                        limbo-height = 384

                        # 玩家进入 limbo 后 Tab 列表头部文案（MiniMessage 格式，支持 <gold> 等颜色标签）
                        # 中英文各一条，按玩家客户端语言自动选择；<newline> 表示换行
                        tablist-header-zh = "这是你的神秘梦境<newline>输入 <gold>/hub</gold> 醒过来"
                        tablist-header-en = "You have fallen asleep<newline>type <gold>/hub</gold> in chat to wake up"

                        # 挂机倒计时开始时的警告副标题（MiniMessage 格式），后面的 ... 由插件自动动态追加
                        warning-subtitle-zh = "即将进入挂机状态"
                        warning-subtitle-en = "You are about to enter AFK mode"

                        # 玩家进入 limbo 后显示的副标题（MiniMessage 格式）
                        limbo-title-zh = "输入 <gold>/hub</gold> 返回大厅"
                        limbo-title-en = "type <gold>/hub</gold> to return to lobby"
                        """);
            }
            config = new Toml().read(file.toFile());
            Long timeout = config.getLong("afk-timeout-seconds", 300L);
            afkTimeoutSeconds = timeout != null && timeout > 0 ? timeout : 300L;
            Long warning = config.getLong("afk-timeout-warning-seconds", 60L);
            long warningSeconds = warning != null && warning > 0 ? warning : 60L;
            afkTimeoutWarningSeconds = Math.clamp(afkTimeoutSeconds - 1, 0, warningSeconds);
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
                .setName("imagination")
                .setViewDistance(getInt("limbo-view-distance", 2))
                .setSimulationDistance(getInt("limbo-simulate-distance", 5))
                .setShouldRespawn(true)
                .registerCommand(new LimboCommandMeta(List.of("hub")))
                .setShouldRejoin(true)
                .setGameMode(GameMode.ADVENTURE)
                .setIsHardCore(false)
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
            // ---- 调试：输出所有区块及是否为空 ----
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

    private void refreshPlayerActivityTimer(Player player) {
        long now = System.currentTimeMillis();
        UUID playerUuid = player.getUniqueId();
        if (
                now - lastActivity.getOrDefault(playerUuid, now) > afkTimeoutSeconds * 1000L - afkTimeoutWarningSeconds * 1000L
                && isAfkLimboEnabled(player)
        ) {
            player.clearTitle();
        }
        lastActivity.put(playerUuid, System.currentTimeMillis());
    }

    public boolean sendToLimbo(Player player) {
        // prevent spam
        refreshPlayerActivityTimer(player);
        if (afkLimbo == null) {
            logger.warn("trying to send player {} to limbo while limbo is not ready!", player.getUsername());
            return false;
        }
        if (limboPlayers.containsKey(player.getUniqueId())) {
            logger.warn("trying to send player {} to limbo while he is already in limbo!", player.getUsername());
            return false;
        }
        afkLimbo.spawnPlayer(player, new LimboSessionHandler() {
            @Override
            public void onChat(String message) {
                LimboPlayer limboPlayer = limboPlayers.get(player.getUniqueId());
                if (message.startsWith("/hub ") || message.equalsIgnoreCase("/hub")) {
                    if (limboPlayer == null) {
                        logger.warn("Unable to find player in limbo: {}!", player.getUsername());
                        return;
                    }
                    player.clearTitle();
                    limboPlayers.remove(player.getUniqueId());
                    limboPlayer.disconnect();
                }
            }
            @Override
            public void onSpawn(Limbo server, LimboPlayer player) {
                player.sendAbilities(
                        AbilityFlags.INVULNERABLE | AbilityFlags.ALLOW_FLYING,
                        0.05f,
                        0.1f
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
                String template = chinese
                        ? config.getString("tablist-header-zh", "这是你的神秘梦境<newline>输入 <gold>/hub</gold> 醒过来")
                        : config.getString("tablist-header-en", "You have fallen asleep<newline>type '<gold>/hub</gold>' in chat to wake up");
                return MiniMessage.miniMessage().deserialize(template);
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

    public long getAfkTimeoutWarningSeconds() {
        return afkTimeoutWarningSeconds;
    }

    /** 修改挂机超时时间并写回 config.toml（同时保留其他所有配置项） */
    public void setAfkTimeoutSeconds(long seconds) {
        afkTimeoutSeconds = seconds;
        // 保证 warning 不超过 timeout-1
        if (afkTimeoutWarningSeconds > afkTimeoutSeconds - 1) {
            afkTimeoutWarningSeconds = Math.max(afkTimeoutSeconds - 1, 0);
        }
        saveConfig();
    }

    /** 修改进入 limbo 前的提醒时间（秒）并写回 config.toml */
    public void setAfkTimeoutWarningSeconds(long seconds) {
        afkTimeoutWarningSeconds = Math.clamp(seconds, 0, Math.max(afkTimeoutSeconds - 1, 0));
        saveConfig();
    }

    private void saveConfig() {
        try {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("afk-timeout-seconds", afkTimeoutSeconds);
            values.put("afk-timeout-warning-seconds", afkTimeoutWarningSeconds);
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
            values.put("limbo-view-distance", getInt("limbo-view-distance", 2));
            values.put("limbo-simulate-distance", getInt("limbo-simulate-distance", 5));
            values.put("limbo-min-y", getInt("limbo-min-y", -64));
            values.put("limbo-height", getInt("limbo-height", 384));
            values.put("tablist-header-zh", config.getString("tablist-header-zh", "这是你的神秘梦境<newline>输入 <gold>/hub</gold> 醒过来"));
            values.put("tablist-header-en", config.getString("tablist-header-en", "You have fallen asleep<newline>type <gold>/hub</gold> in chat to wake up"));
            values.put("warning-subtitle-zh", config.getString("warning-subtitle-zh", "即将进入挂机状态"));
            values.put("warning-subtitle-en", config.getString("warning-subtitle-en", "You are about to enter AFK mode"));
            values.put("limbo-title-zh", config.getString("limbo-title-zh", "输入 <gold>/hub</gold> 返回大厅"));
            values.put("limbo-title-en", config.getString("limbo-title-en", "type <gold>/hub</gold> to return to lobby"));
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
                Player player = event.getPlayer();
                if (player == null) return;
                if (shouldResetTimer(event.getPacketType())) {
                    refreshPlayerActivityTimer(player);
                }
                if (event.getPacketType() == PacketType.Play.Client.PLAYER_POSITION) {
                    WrapperPlayClientPlayerPosition wrapper = new WrapperPlayClientPlayerPosition(event);
                    lastPositions.put(player.getUniqueId(), wrapper.getPosition());
                } else if (event.getPacketType() == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION) {
                    WrapperPlayClientPlayerPositionAndRotation wrapper = new WrapperPlayClientPlayerPositionAndRotation(event);
                    lastPositions.put(player.getUniqueId(), wrapper.getPosition());
                }
            }
            @Override
            public void onPacketSend(@NonNull PacketSendEvent event) {
                if (event.getPacketType() == PacketType.Play.Server.PLAYER_POSITION_AND_LOOK) {
                    Player player = event.getPlayer();
                    WrapperPlayServerPlayerPositionAndLook wrapper = new WrapperPlayServerPlayerPositionAndLook(event);
                    lastPositions.put(player.getUniqueId(), wrapper.getPosition());
                }
            }
        }, PacketListenerPriority.NORMAL);
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        refreshPlayerActivityTimer(event.getPlayer());
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        lastActivity.remove(event.getPlayer().getUniqueId());
        lastPositions.remove(event.getPlayer().getUniqueId());
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
                || type == PacketType.Play.Client.ANIMATION
                || type == PacketType.Play.Client.CLICK_WINDOW
                || type == PacketType.Play.Client.CLICK_WINDOW_BUTTON
                || type == PacketType.Play.Client.CLOSE_WINDOW
                ;
    }

    // ---------- 定时检查 ----------

    private void startTasks() {
        server.getScheduler().buildTask(this, () -> {
            long now = System.currentTimeMillis();
            long timeoutMs = afkTimeoutSeconds * 1000L;
            long timeoutWarningMs = afkTimeoutSeconds * 1000L - afkTimeoutWarningSeconds * 1000L;

            for (Map.Entry<UUID, Long> entry : lastActivity.entrySet()) {
                Player player = server.getPlayer(entry.getKey()).orElse(null);
                if (player == null) {
                    lastActivity.remove(entry.getKey());
                    continue;
                }
                if (!isAfkLimboEnabled(player)) {
                    continue;
                }
                if (now - entry.getValue() > timeoutWarningMs) {
                    player.showTitle(Title.title(
                            Component.empty(),
                            getWarningSubtitleMessage(player, now - entry.getValue()),
                            Title.Times.times(Duration.ZERO, Duration.ofSeconds(11), Duration.ZERO)
                    ));
                    Vector3d position = lastPositions.get(player.getUniqueId());
                    Object playerChannel = PacketEvents.getAPI().getPlayerManager().getChannel(player);
                    if (position != null && playerChannel != null) {
                        WrapperPlayServerSoundEffect wrapper = new WrapperPlayServerSoundEffect(
                                Sounds.BLOCK_BELL_RESONATE.getId(ClientVersion.getById(player.getProtocolVersion().getProtocol())),
                                SoundCategory.UI,
                                position,
                                1f,
                                1.75f,
                                0
                        );
                        PacketEvents.getAPI().getProtocolManager().sendPacket(playerChannel, wrapper);
                    }
                }
                if (now - entry.getValue() < timeoutMs) {
                    continue;
                }
                sendToLimbo(player);
            }
        }).repeat(10, TimeUnit.SECONDS).schedule();
    }

    private @NonNull Component getTitleMessage(LimboPlayer player) {
        boolean chinese = player.getProxyPlayer().getEffectiveLocale() == Locale.CHINA;
        String template = chinese
                ? config.getString("limbo-title-zh", "输入 <gold>/hub</gold> 返回大厅")
                : config.getString("limbo-title-en", "type '<gold>/hub</gold>' to return to lobby");
        return MiniMessage.miniMessage().deserialize(template);
    }

    private @NonNull Component getWarningSubtitleMessage(Player player, long elapsedMs) {
        // elapsedMs = 距上次活动已过去的时间（挂机时长），由调用处传入。
        // 点随临近超时递增：进入警告区后每约 10 秒加一点，封顶 3（省略号效果）。
        long warningStartMs = (afkTimeoutSeconds - afkTimeoutWarningSeconds) * 1000L; // 警告区起点(elapsed)
        long warningElapsedMs = elapsedMs - warningStartMs;                          // 进入警告区后经过多久(>=0)
        int dots = 1 + (int) (Math.max(0, warningElapsedMs) / 10_000L);
        dots %= 8;

        boolean chinese = player.getEffectiveLocale() == Locale.CHINA;
        String template = chinese
                ? config.getString("warning-subtitle-zh", "即将进入挂机状态")
                : config.getString("warning-subtitle-en", "You are about to enter AFK mode");
        Component message = MiniMessage.miniMessage().deserialize(template);
        for (int i = 0; i < dots; i++) {
            message = message.append(Component.text("."));
        }
        return message;
    }

    private record BlockPos(int x, int y, int z){}
}
