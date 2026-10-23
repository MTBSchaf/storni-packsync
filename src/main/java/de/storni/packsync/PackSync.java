package de.storni.packsync;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Holt regelmaessig den SHA-1-Hash des Resource Packs von GitHub und
 * schickt das Pack beim Join (und bei Aenderungen) an alle Spieler.
 * Folia-kompatibel: HTTP-Anfragen laufen ueber den Async-Scheduler,
 * Spieleraktionen ueber den Entity-Scheduler des jeweiligen Spielers.
 */
public final class PackSync extends JavaPlugin implements Listener {

    private static final Pattern SHA1 = Pattern.compile("^[0-9a-fA-F]{40}$");
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private volatile String currentHash;   // 40 Hex-Zeichen
    private volatile String packUrl;
    private volatile String hashUrl;
    private volatile boolean force;
    private volatile Component prompt;
    private volatile Component kickMessage;
    private volatile UUID packId;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();

        getServer().getPluginManager().registerEvents(this, this);

        long interval = Math.max(10, getConfig().getLong("check-interval-seconds", 60));
        Bukkit.getAsyncScheduler().runAtFixedRate(this, task -> refreshHash(), 1, interval, TimeUnit.SECONDS);

        getLogger().info("PackSync aktiv. Pruefe alle " + interval + "s auf ein neues Pack.");
    }

    private void loadSettings() {
        reloadConfig();
        packUrl = getConfig().getString("pack-url", "");
        hashUrl = getConfig().getString("hash-url", "");
        force = getConfig().getBoolean("force", true);
        prompt = MM.deserialize(getConfig().getString("prompt", ""));
        kickMessage = MM.deserialize(getConfig().getString("kick-message", ""));
        // Feste ID aus der URL: ein neues Pack ersetzt beim Client das alte, statt sich zu stapeln
        packId = UUID.nameUUIDFromBytes(packUrl.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Laeuft auf dem Async-Scheduler (nicht auf einem Region-Thread). */
    private void refreshHash() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(hashUrl))
                    .timeout(Duration.ofSeconds(15))
                    .header("Cache-Control", "no-cache")
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                getLogger().warning("Hash-Abruf fehlgeschlagen: HTTP " + response.statusCode());
                return;
            }

            String hash = response.body().trim().toLowerCase();
            if (!SHA1.matcher(hash).matches()) {
                getLogger().warning("pack.sha1 enthaelt keinen gueltigen SHA-1-Hash (40 Hex-Zeichen): " + hash);
                return;
            }

            if (hash.equals(currentHash)) {
                return;
            }

            boolean firstLoad = currentHash == null;
            currentHash = hash;
            getLogger().info((firstLoad ? "Pack-Hash geladen: " : "Neues Pack erkannt: ") + hash);

            if (!firstLoad) {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    player.getScheduler().run(this, t -> sendPack(player), null);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            getLogger().warning("Hash-Abruf fehlgeschlagen: " + e.getMessage());
        }
    }

    /** Muss auf dem Entity-Scheduler des Spielers laufen. */
    private void sendPack(Player player) {
        String hash = currentHash;
        if (hash == null) {
            getLogger().warning("Noch kein Hash geladen, Pack wird " + player.getName() + " nicht geschickt.");
            return;
        }
        player.setResourcePack(packId, packUrl, HexFormat.of().parseHex(hash), prompt, force);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // kurz warten, bis der Spieler im Spiel ist
        player.getScheduler().runDelayed(this, t -> sendPack(player), null, 20L);
    }

    @EventHandler
    public void onStatus(PlayerResourcePackStatusEvent event) {
        if (!force || !event.getID().equals(packId)) {
            return;
        }
        Player player = event.getPlayer();
        switch (event.getStatus()) {
            case DECLINED, FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD ->
                    player.getScheduler().run(this, t -> player.kick(kickMessage), null);
            default -> { }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        loadSettings();
        sender.sendMessage(Component.text("PackSync: Pruefe auf ein neues Pack ..."));
        Bukkit.getAsyncScheduler().runNow(this, t -> refreshHash());
        String hash = currentHash;
        sender.sendMessage(Component.text("Aktueller Hash: " + (hash == null ? "noch keiner geladen" : hash)));
        return true;
    }
}
