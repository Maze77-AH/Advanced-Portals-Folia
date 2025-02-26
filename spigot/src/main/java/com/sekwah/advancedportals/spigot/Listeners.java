package com.sekwah.advancedportals.spigot;

import com.sekwah.advancedportals.core.CoreListeners;
import com.sekwah.advancedportals.core.repository.ConfigRepository;
import com.sekwah.advancedportals.core.serializeddata.BlockLocation;
import com.sekwah.advancedportals.core.services.PortalServices;
import com.sekwah.advancedportals.spigot.connector.container.SpigotEntityContainer;
import com.sekwah.advancedportals.spigot.connector.container.SpigotPlayerContainer;
import com.sekwah.advancedportals.spigot.connector.container.SpigotWorldContainer;
import com.sekwah.advancedportals.spigot.utils.ContainerHelpers;
import java.lang.reflect.Method;
import java.util.List;
import javax.inject.Inject;
import org.bukkit.Axis;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.EndGateway;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Some of these events are passed to the core listener to handle, while others
 * are handled directly here. Reflection is used to safely call EndGateway#setAge(long)
 * if available.
 */
public class Listeners implements Listener {
    @Inject
    private CoreListeners coreListeners;

    @Inject
    private PortalServices portalServices;

    @Inject
    private ConfigRepository configRepository;

    /**
     * Reflection approach: If EndGateway#setAge(long) exists, store the Method here.
     */
    private static final Method endGatewaySetAgeMethod;
    private static final boolean endGatewaySetAgeExists;

    static {
        Method tempMethod = null;
        boolean tempExists = false;
        try {
            // Attempt to retrieve setAge(long) from EndGateway
            tempMethod = EndGateway.class.getMethod("setAge", long.class);
            tempExists = true;
        } catch (NoSuchMethodException ignored) {
        }
        endGatewaySetAgeMethod = tempMethod;
        endGatewaySetAgeExists = tempExists;
    }

    // Example helper to safely set Age:
    private void disableEndGateway(BlockState state) {
        if (!endGatewaySetAgeExists) {
            return; // Not supported on this server version
        }
        if (!(state instanceof EndGateway)) {
            return;
        }
        try {
            endGatewaySetAgeMethod.invoke(state, Long.MIN_VALUE);
            state.update();
        } catch (Exception ex) {
            // If something goes wrong (e.g. security or invocation issues),
            // just ignore or log it.
            Bukkit.getLogger().warning("Failed to invoke EndGateway#setAge via reflection: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------------------
    // Player / Entity events
    // ------------------------------------------------------------------------

    @EventHandler
    public void onJoinEvent(PlayerJoinEvent event) {
        coreListeners.playerJoin(new SpigotPlayerContainer(event.getPlayer()));
    }

    @EventHandler
    public void onPlayerQuitEvent(PlayerQuitEvent event) {
        coreListeners.playerLeave(new SpigotPlayerContainer(event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMoveEvent(PlayerMoveEvent event) {
        Location to = event.getTo();
        if (to == null) return;
        coreListeners.playerMove(new SpigotPlayerContainer(event.getPlayer()),
                ContainerHelpers.toPlayerLocation(to));
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityPortalEvent(EntityPortalEvent event) {
        Entity ent = event.getEntity();
        if (!this.coreListeners.entityPortalEvent(new SpigotEntityContainer(ent))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPortalEvent(PlayerPortalEvent event) {
        if (!this.coreListeners.playerPortalEvent(
                new SpigotPlayerContainer(event.getPlayer()),
                ContainerHelpers.toPlayerLocation(event.getFrom()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamEvent(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        EntityDamageEvent.DamageCause cause = event.getCause();
        if (cause == EntityDamageEvent.DamageCause.LAVA
            || cause == EntityDamageEvent.DamageCause.FIRE
            || cause == EntityDamageEvent.DamageCause.FIRE_TICK) {
            if (this.coreListeners.preventEntityCombust(
                    new SpigotEntityContainer(event.getEntity()))) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onCombustEntityEvent(EntityCombustEvent event) {
        if (this.coreListeners.preventEntityCombust(
                new SpigotEntityContainer(event.getEntity()))) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------------
    // Block / Portal region events
    // ------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (event.isCancelled()) return;
        Location blockloc = event.getBlock().getLocation();
        if (blockloc.getWorld() == null) return;

        ItemStack inHand = event.getItemInHand();
        if (inHand.getItemMeta() == null) return;

        boolean allowed = coreListeners.blockPlace(
            new SpigotPlayerContainer(event.getPlayer()),
            new BlockLocation(blockloc.getWorld().getName(),
                              blockloc.getBlockX(),
                              blockloc.getBlockY(),
                              blockloc.getBlockZ()),
            event.getBlockPlaced().getType().toString(),
            inHand.getType().toString(),
            inHand.getItemMeta().getDisplayName()
        );

        if (!allowed) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPhysicsEvent(BlockPhysicsEvent event) {
        if (!coreListeners.physicsEvent(
                ContainerHelpers.toBlockLocation(event.getBlock().getLocation()),
                event.getBlock().getType().toString())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onWorldChangeEvent(PlayerChangedWorldEvent event) {
        coreListeners.worldChange(new SpigotPlayerContainer(event.getPlayer()));
    }

    @EventHandler
    public void onItemInteract(PlayerInteractEvent event) {
        if (event.isCancelled()) return;
        if (event.getItem() == null || event.getClickedBlock() == null) return;
        if (event.getItem().getItemMeta() == null) return;

        Location blockloc = event.getClickedBlock().getLocation();
        if (blockloc.getWorld() == null) return;

        boolean allowEvent = coreListeners.playerInteractWithBlock(
            new SpigotPlayerContainer(event.getPlayer()),
            event.getClickedBlock().getType().toString(),
            event.getMaterial().toString(),
            event.getItem().getItemMeta().getDisplayName(),
            new BlockLocation(blockloc.getWorld().getName(),
                              blockloc.getBlockX(),
                              blockloc.getBlockY(),
                              blockloc.getBlockZ()),
            event.getAction() == Action.LEFT_CLICK_BLOCK
        );
        if (!allowEvent) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void spawnMobEvent(CreatureSpawnEvent event) {
        if (event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.NETHER_PORTAL) {
            if (portalServices.inPortalRegionProtected(
                ContainerHelpers.toPlayerLocation(event.getLocation()))) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockFromTo(BlockFromToEvent event) {
        if (!configRepository.getStopWaterFlow()) {
            return;
        }
        boolean fromAllowed = coreListeners.blockPlace(
            null,
            ContainerHelpers.toBlockLocation(event.getBlock().getLocation()),
            event.getBlock().getType().toString(),
            null, null
        );
        boolean toAllowed = coreListeners.blockPlace(
            null,
            ContainerHelpers.toBlockLocation(event.getToBlock().getLocation()),
            event.getBlock().getType().toString(),
            null, null
        );
        if (!fromAllowed || !toAllowed) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockBreak(BlockBreakEvent event) {
        ItemStack itemInHand = event.getPlayer().getItemInHand();
        String handType = itemInHand == null ? null : itemInHand.getType().toString();
        String handDisplay = (itemInHand == null || itemInHand.getItemMeta() == null)
                ? null : itemInHand.getItemMeta().getDisplayName();

        boolean allowed = coreListeners.blockBreak(
            new SpigotPlayerContainer(event.getPlayer()),
            ContainerHelpers.toBlockLocation(event.getBlock().getLocation()),
            event.getBlock().getType().toString(),
            handType,
            handDisplay
        );
        if (!allowed) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onExplosion(EntityExplodeEvent event) {
        if (!configRepository.getPortalProtection()) return;
        List<Block> blockList = event.blockList();
        for (int i = 0; i < blockList.size(); i++) {
            Block block = blockList.get(i);
            if (portalServices.inPortalRegionProtected(
                    ContainerHelpers.toBlockLocation(block.getLocation()))) {
                blockList.remove(i);
                i--;
            }
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!configRepository.getDisableGatewayBeam()) {
            return;
        }
        // We just check if the chunk has any EndGateway inside a portal region.
        BlockState[] tileEntities = event.getChunk().getTileEntities();
        for (BlockState blockState : tileEntities) {
            if (blockState.getType() == Material.END_GATEWAY) {
                Location loc = blockState.getLocation();
                // Check if in portal region with radius=2
                if (portalServices.inPortalRegion(
                    new BlockLocation(loc.getWorld().getName(),
                                      loc.getBlockX(), loc.getBlockY(),
                                      loc.getBlockZ()), 2)) {
                    // Attempt reflection-based setAge(Long.MIN_VALUE)
                    disableEndGateway(blockState);
                }
            }
        }
    }
}
