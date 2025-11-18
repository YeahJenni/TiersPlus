package com.yeahjenni.ocetiertagger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.yeahjenni.ocetiertagger.config.TierTaggerConfig;
import com.yeahjenni.ocetiertagger.config.UkulibIntegration;
import com.yeahjenni.ocetiertagger.model.GameMode;
import com.yeahjenni.ocetiertagger.model.PlayerInfo;
import com.yeahjenni.ocetiertagger.model.OCETierPlayer;
import com.yeahjenni.ocetiertagger.TierCache.TierInfo;
import com.yeahjenni.ocetiertagger.TierCache;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import net.minecraft.SharedConstants;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.uku3lig.ukulib.config.ConfigManager;
import net.uku3lig.ukulib.utils.PlayerArgumentType;
import net.uku3lig.ukulib.utils.Ukutils;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public class ocetiertagger implements ClientModInitializer {
    public static final String MOD_ID = "ocetiertagger";
    private static final String UPDATE_URL_FORMAT = "https://api.modrinth.com/v2/project/yoB88RtH/version?game_versions=%s";

    public static final Gson GSON = new GsonBuilder().create();

    private static final ConfigManager<TierTaggerConfig> manager = 
        ConfigManager.createDefault(TierTaggerConfig.class, MOD_ID);

    private static final Logger logger = LoggerFactory.getLogger(MOD_ID);
    private static Version latestVersion = null;
    private static final HttpClient client = HttpClient.newHttpClient();

    // === version checker stuff ===
    private static final AtomicBoolean isObsolete = new AtomicBoolean(false);

    private static int tickCounter = 0;

    private static TierTaggerConfig.TierlistSource lastTierlistSource = TierTaggerConfig.TierlistSource.OCETIERS;

    @Override
    public void onInitializeClient() {
        TierCache.clearCache();

        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            TierCache.clearCache();
            logger.info("tiersplus cache cleared on game exit");
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) -> dispatcher.register(
                literal(MOD_ID)
                        .then(argument("player", PlayerArgumentType.player())
                                .executes(ocetiertagger::displayTierInfo))));

        KeyBinding keyBinding = KeyBindingHelper.registerKeyBinding(
            new KeyBinding("ocetiertagger.keybind.gamemode", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, "ocetiertagger.name")
        );

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (keyBinding.wasPressed()) {
                cycleGamemode();
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            TierTaggerConfig config = manager.getConfig();
            if (config.getTierlistSource() != lastTierlistSource) {
                TierCache.clearCache();
                lastTierlistSource = config.getTierlistSource();
                logger.info("Tierlist source changed to {}, cache cleared", lastTierlistSource);
            }

            tickCounter++;

            if (client.world != null && client.player != null && tickCounter % 200 == 0) {
                client.world.getPlayers().forEach(player -> {
                    String username = player.getNameForScoreboard();
                    if (username != null && !username.isEmpty() && isValidUsername(username)) {
                        TierCache.fetchPlayerByUsername(username);
                    }
                });
            }
        });

        checkForUpdates();
    }

    private static boolean isValidUsername(String username) {
        String usernamePattern = "^[a-zA-Z0-9_]{3,16}$"; 
        return username.matches(usernamePattern);
    }

    public static Text appendTier(PlayerEntity player, Text text) {
        if (!manager.getConfig().isEnabled()) {
            return text;
        }

        String username = player.getName().getString();
        String gameMode;
        String tier;
        
        if (manager.getConfig().isShowBestTierFirst()) {
            TierInfo bestTier = TierCache.getBestTier(username);
            if (bestTier == null) return text;
            
            gameMode = bestTier.getGameMode();
            tier = bestTier.getTier();
        } else {
            gameMode = manager.getConfig().getGameMode();
            tier = TierCache.getCachedTier(username, gameMode);
            if (tier == null) return text;
        }

        OCETierPlayer playerData = TierCache.getPlayerData(username);
        boolean isOceaniasStaff = playerData != null && playerData.oceaniasStaff();
        boolean isOwner = playerData != null && playerData.owner();
        boolean isAve = playerData != null && playerData.ave();

        int color = getTierColor(tier);
        String iconChar = TierCache.GAMEMODE_ICON_CHARS.getOrDefault(gameMode, "");
        
        MutableText iconText = Text.empty().append(
            Text.literal(iconChar).styled(s -> s.withFont(Identifier.of("ocetiertagger", "icons")))
        );
        
        MutableText tierText = Text.literal(tier)
            .styled(style -> style.withColor(color));
        
        if (manager.getConfig().isShowLeaderboardPosition() && playerData != null && playerData.leaderboardPosition() > 0) {
            tierText = tierText.append(Text.literal(" #" + playerData.leaderboardPosition())
                .styled(style -> style.withColor(0xAAAAAA))); 
        }
        
        MutableText divider = Text.literal(" | ");
        MutableText space = Text.literal(" ");
        
        MutableText staffIcon = Text.empty();
        if (isOwner) {
            staffIcon = Text.empty().append(
                Text.literal(TierCache.OWNER_ICON)
                    .styled(s -> s.withColor(0xFFD700)) 
            );
        } else if (isOceaniasStaff) {
            staffIcon = Text.empty().append(
                Text.literal(TierCache.OCEANIAS_STAFF_ICON)
                    .styled(s -> s.withColor(0xBF00FF))
            );
        } else if (isAve) {
            staffIcon = Text.empty().append(
                Text.literal(TierCache.AVE_ICON)
                    .styled(s -> s.withColor(0x00AAFF))
            );
        }
        
        if (manager.getConfig().getNametagPosition() == TierTaggerConfig.NametagPosition.LEFT) {
            return Text.empty()
                .append(isOceaniasStaff || isOwner || isAve ? staffIcon.append(space) : Text.empty())
                .append(iconText)
                .append(space)
                .append(tierText)
                .append(divider)
                .append(text);
        } else {
            return Text.empty()
                .append(text)
                .append(divider)
                .append(tierText)
                .append(space)
                .append(iconText)
                .append(isOceaniasStaff || isOwner || isAve ? space.copy().append(staffIcon) : Text.empty());
        }
    }

    public static int getTierColor(String tier) {
        if (tier == null) return 0xD3D3D3;

        return manager.getConfig().getTierColors().getOrDefault(tier, 0xD3D3D3);
    }

    private static int displayTierInfo(CommandContext<FabricClientCommandSource> ctx) {
        PlayerArgumentType.PlayerSelector selector = ctx.getArgument("player", PlayerArgumentType.PlayerSelector.class);

        Optional<PlayerInfo> playerInfo = ctx.getSource().getWorld().getPlayers().stream()
                .filter(p -> p.getNameForScoreboard().equalsIgnoreCase(selector.name()) || p.getUuidAsString().equalsIgnoreCase(selector.name()))
                .findFirst()
                .map(Entity::getUuid)
                .map(uuid -> {
                    try {
                        MinecraftClient client = MinecraftClient.getInstance();
                        PlayerEntity player = client.world.getPlayerByUuid(uuid);
                        String username = player.getName().getString();

                        return TierCache.getPlayerInfo(username).get(200, TimeUnit.MILLISECONDS); 
                    } catch (Exception e) {
                        return null;
                    }
                });

        if (playerInfo.isPresent()) {
            ctx.getSource().sendFeedback(printPlayerInfo(playerInfo.get()));
        } else {
            ctx.getSource().sendFeedback(Text.of("[ocetiertagger] Searching..."));
            TierCache.searchPlayer(selector.name())
                    .thenAccept(p -> ctx.getSource().sendFeedback(printPlayerInfo(p)))
                    .exceptionally(t -> {
                        ctx.getSource().sendError(Text.of("Could not find player " + selector.name()));
                        return null;
                    });
        }

        return 0;
    }

    private static Text printPlayerInfo(PlayerInfo info) {
        MutableText text = Text.empty().append("=== Rankings for " + info.getName() + " ===");

        info.getRankings().forEach((m, r) -> {
            if (m == null) return;
            GameMode mode = TierCache.findMode(m);
            String tier = getTierText(r);

            String iconChar = TierCache.GAMEMODE_ICON_CHARS.getOrDefault(m, "");
            
            MutableText iconText = Text.literal(iconChar)
                .styled(s -> s.withFont(Identifier.of("ocetiertagger", "icons")));
            
            Text tierText = Text.literal(tier).styled(s -> s.withColor(getTierColor(tier)));
            
            text.append(Text.literal("\n"))
                .append(iconText)
                .append(" ")
                .append(mode.getTitle())
                .append(": ")
                .append(tierText);
        });

        return text;
    }

    @NotNull
    public static String getTierText(PlayerInfo.Ranking ranking) {
        if (manager.getConfig().isShowRetired() && ranking.isRetired() && 
            ranking.getPeakTier() != null && ranking.getPeakPos() > -1) {
            return "R" + ranking.getPeakTier(); 
        } else {
            return ranking.getTier(); 
        }
    }

    private static void checkForUpdates() {
        String gameVersionName;
        try {
            gameVersionName = SharedConstants.getGameVersion().getName();
        } catch (NoSuchMethodError e) {
            gameVersionName = SharedConstants.getGameVersion().toString();
        }

        String versionParam = "[\"%s\"]".formatted(gameVersionName);
        String fullUrl = UPDATE_URL_FORMAT.formatted(URLEncoder.encode(versionParam, StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder(URI.create(fullUrl)).GET().build();

        client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(r -> {
                    String body = r.body();
                    JsonArray array = GSON.fromJson(body, JsonArray.class);

                    if (!array.isEmpty()) {
                        JsonObject root = array.get(0).getAsJsonObject();

                        String versionName = root.get("name").getAsString();
                        if (versionName != null && versionName.toLowerCase(Locale.ROOT).startsWith("[o")) {
                            isObsolete.set(true);
                        }

                        String latestVer = root.get("version_number").getAsString();
                        try {
                            return Version.parse(latestVer);
                        } catch (VersionParsingException e) {
                            logger.warn("Could not parse version number {}", latestVer);
                        }
                    }

                    return null;
                })
                .exceptionally(t -> {
                    logger.warn("Error checking for updates", t);
                    return null;
                }).thenAccept(v -> {
                    logger.info("Found latest version {}", v.getFriendlyString());
                    latestVersion = v;
                });
    }

    public static boolean isObsolete() {
        return isObsolete.get();
    }

    private void cycleGamemode() {
        String nextMode = TierCache.findNextMode(manager.getConfig().getGameMode());
        manager.getConfig().setGameMode(nextMode);
        manager.saveConfig();

        TierCache.clearCache();

        String displayName = TierCache.getGameModeDisplay(nextMode);
        Ukutils.sendToast(
                Text.literal("Game mode switched!"),
                Text.literal("Now showing ").append(Text.literal(displayName).formatted(Formatting.GOLD)).append(" tiers.")
        );
    }

    public static ConfigManager<TierTaggerConfig> getManager() {
        return manager;
    }

    public static Version getLatestVersion() {
        return latestVersion;
    }

    public static Logger getLogger() {
        return logger;
    }
}
