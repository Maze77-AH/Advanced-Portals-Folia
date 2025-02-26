package com.sekwah.advancedportals.spigot.connector.container;

import com.sekwah.advancedportals.core.AdvancedPortalsCore;
import com.sekwah.advancedportals.core.connector.containers.GameMode;
import com.sekwah.advancedportals.core.connector.containers.PlayerContainer;
import com.sekwah.advancedportals.core.connector.containers.ServerContainer;
import com.sekwah.advancedportals.core.serializeddata.BlockLocation;
import com.sekwah.advancedportals.core.serializeddata.PlayerLocation;
import com.sekwah.advancedportals.core.serializeddata.Vector;
import com.sekwah.advancedportals.spigot.AdvancedPortalsPlugin;
import net.kyori.adventure.text.Component;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import javax.inject.Inject;
import java.util.Arrays;
import java.util.UUID;

/**
 * A container for interacting with a Spigot/Paper/Folia Player object.
 */
public class SpigotPlayerContainer extends SpigotEntityContainer implements PlayerContainer {

    @Inject
    private AdvancedPortalsCore portalsCore;

    private final Player player;

    public SpigotPlayerContainer(Player player) {
        super(player);
        this.player = player;
    }

    @Override
    public UUID getUUID() {
        return player.getUniqueId();
    }

    @Override
    public void sendMessage(String message) {
        player.sendMessage(message);
    }

    /**
     * Attempts to use the modern Paper/Folia method {@code player.sendActionBar(Component)}.
     * If that fails (on older Spigot/Paper), it falls back to {@code player.spigot().sendMessage(...)}.
     */
    @Override
    public void sendActionBar(String message) {
        player.sendActionBar(Component.text(message));
    }

    @Override
    public boolean isOp() {
        return this.player.isOp();
    }

    @Override
    public boolean teleport(PlayerLocation location) {
        Location bukkitLoc = new Location(
            Bukkit.getWorld(location.getWorldName()),
            location.getPosX(),
            location.getPosY(),
            location.getPosZ(),
            location.getYaw(),
            location.getPitch()
        );
        return this.player.teleport(bukkitLoc);
    }

    @Override
    public boolean hasPermission(String permission) {
        return this.player.hasPermission(permission);
    }

    @Override
    public void giveItem(String material, String itemName, String... itemDescription) {
        Material mat = Material.getMaterial(material);
        if (mat == null) {
            return;
        }
        ItemStack stack = new ItemStack(mat);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(itemName);
            meta.setLore(Arrays.asList(itemDescription));
            stack.setItemMeta(meta);
        }
        this.player.getInventory().addItem(stack);
    }

    @Override
    public boolean sendPacket(String channel, byte[] bytes) {
        // Folia/Spigot plugin messaging is the same:
        player.sendPluginMessage(AdvancedPortalsPlugin.getInstance(), channel, bytes);
        return true;
    }

    @Override
    public GameMode getGameMode() {
        try {
            return GameMode.valueOf(this.player.getGameMode().name());
        } catch (IllegalArgumentException e) {
            return GameMode.SURVIVAL;
        }
    }

    public Player getPlayer() {
        return this.player;
    }

    @Override
    public void playSound(String sound, float volume, float pitch) {
        this.player.playSound(this.player.getLocation(), sound, volume, pitch);
    }

    @Override
    public ServerContainer getServer() {
        return new SpigotServerContainer(this.player.getServer());
    }

    @Override
    public void spawnColoredDust(Vector position, double xSpread, double ySpread, double zSpread, int count, java.awt.Color color) {
        // If the player is too far away, skip
        if (this.player.getLocation().distance(
                new Location(this.player.getWorld(), position.getX(), position.getY(), position.getZ())
            ) > 180) {
            return;
        }

        // Convert AWT color to Bukkit color
        Particle.DustOptions dustOptions = new Particle.DustOptions(
            Color.fromRGB(color.getRed(), color.getGreen(), color.getBlue()),
            1.5f
        );
        this.player.spawnParticle(
            Particle.SQUID_INK,
            position.getX(), position.getY(), position.getZ(),
            count,
            xSpread, ySpread, zSpread,
            count, // speed or extra param
            dustOptions
        );
    }
}
