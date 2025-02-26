package com.sekwah.advancedportals.spigot;

import com.google.inject.Injector;
import com.sekwah.advancedportals.core.AdvancedPortalsCore;
import com.sekwah.advancedportals.core.connector.commands.CommandRegister;
import com.sekwah.advancedportals.core.module.AdvancedPortalsModule;
import com.sekwah.advancedportals.core.permissions.Permissions;
import com.sekwah.advancedportals.core.repository.ConfigRepository;
import com.sekwah.advancedportals.core.serializeddata.BlockLocation;
import com.sekwah.advancedportals.core.services.DestinationServices;
import com.sekwah.advancedportals.core.services.PortalServices;
import com.sekwah.advancedportals.core.util.GameScheduler;
import com.sekwah.advancedportals.spigot.commands.subcommands.portal.ImportPortalSubCommand;
import com.sekwah.advancedportals.spigot.connector.command.SpigotCommandRegister;
import com.sekwah.advancedportals.spigot.connector.container.SpigotServerContainer;
import com.sekwah.advancedportals.spigot.importer.LegacyImporter;
import com.sekwah.advancedportals.spigot.metrics.Metrics;
import com.sekwah.advancedportals.spigot.tags.ConditionsTag;
import com.sekwah.advancedportals.spigot.tags.CostTag;
import com.sekwah.advancedportals.spigot.warpeffects.SpigotWarpEffects;
import java.io.File;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.EndGateway;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

public class AdvancedPortalsPlugin extends JavaPlugin {
    private AdvancedPortalsCore portalsCore;

    @Inject
    DestinationServices destinationServices;

    @Inject
    PortalServices portalServices;

    @Inject
    ConfigRepository configRepo;

    private static AdvancedPortalsPlugin instance;

    public static AdvancedPortalsPlugin getInstance() {
        return instance;
    }

    public AdvancedPortalsPlugin() {
        instance = this;
    }

    @Override
    public void onEnable() {
        // Start metrics
        new Metrics(this, 4814);

        // For your internal permissions checks
        Permissions.hasPermissionManager = true;

        // Grab server version via regex
        String mcVersion = this.getServer().getVersion();
        Pattern pattern = Pattern.compile("\\(MC: ([\\d.]+)\\)");
        Matcher matcher = pattern.matcher(mcVersion);
        String versionString = matcher.find() ? matcher.group(1) : "0.0.0";

        // Create SpigotServerContainer
        SpigotServerContainer serverContainer = new SpigotServerContainer(this.getServer());

        // Create the core
        this.portalsCore = new AdvancedPortalsCore(
                versionString,
                this.getDataFolder(),
                new SpigotInfoLogger(this),
                serverContainer
        );
        AdvancedPortalsModule module = this.portalsCore.getModule();

        // Provide SpigotCommandRegister to the CommandRegister
        module.addInstanceBinding(CommandRegister.class, new SpigotCommandRegister(this));

        // Grab the Guice injector
        Injector injector = module.getInjector();

        // Inject dependencies
        injector.injectMembers(this);
        injector.injectMembers(this.portalsCore);
        injector.injectMembers(serverContainer);

        // Register the event listeners
        Listeners listeners = injector.getInstance(Listeners.class);
        injector.injectMembers(listeners);
        this.getServer().getPluginManager().registerEvents(listeners, this);

        // Acquire your custom GameScheduler
        GameScheduler scheduler = injector.getInstance(GameScheduler.class);

        // Attempt Folia's global region scheduler (reflection), else fallback to legacy
        try {
            Method getGlobalRegionSchedulerMethod = Bukkit.class.getMethod("getGlobalRegionScheduler");
            Object globalScheduler = getGlobalRegionSchedulerMethod.invoke(null);
            Method runAtFixedRateMethod = globalScheduler.getClass().getMethod(
                    "runAtFixedRate", Plugin.class, Consumer.class, long.class, long.class
            );

            // If we got here, Folia is present. We'll schedule with Folia's runAtFixedRate
            runAtFixedRateMethod.invoke(
                    globalScheduler,
                    this,
                    (Consumer<?>) task -> scheduler.tick(),
                    1L,
                    1L
            );
            getLogger().info("Folia environment detected. Using globalRegionScheduler.");
        } catch (NoSuchMethodException ex) {
            // Probably not Folia
            getLogger().info("Folia not detected. Using legacy scheduleSyncRepeatingTask.");
            getServer().getScheduler().scheduleSyncRepeatingTask(this, scheduler::tick, 1L, 1L);
        } catch (Exception ex) {
            getLogger().warning("Error scheduling via Folia reflection. Falling back. " + ex);
            getServer().getScheduler().scheduleSyncRepeatingTask(this, scheduler::tick, 1L, 1L);
        }

        // Warp Effects
        SpigotWarpEffects warpEffects = new SpigotWarpEffects();
        injector.injectMembers(warpEffects);
        warpEffects.registerEffects();

        // Initialize the core
        this.portalsCore.onEnable();

        // Register subcommands
        this.portalsCore.registerPortalCommand("import", new ImportPortalSubCommand());

        // Create or import config
        checkAndCreateConfig();

        // Hook placeholder API if present
        registerPlaceholderAPI();

        // Hook Vault if present
        checkVault();
    }

    @Override
    public void onDisable() {
        this.portalsCore.onDisable();
    }

    private void checkAndCreateConfig() {
        if (!this.getDataFolder().exists()) {
            this.getDataFolder().mkdirs();
        }

        File destiFile = new File(this.getDataFolder(), "destinations.yml");
        File destiFolder = new File(this.getDataFolder(), "desti");
        if (destiFile.exists() && !destiFolder.exists()) {
            destiFolder.mkdirs();
            getLogger().info("Importing old destinations from destinations.yml");
            LegacyImporter.importDestinations(this.destinationServices);
        }

        File portalFile = new File(this.getDataFolder(), "portals.yml");
        File portalFolder = new File(this.getDataFolder(), "portals");
        if (portalFile.exists() && !portalFolder.exists()) {
            portalFolder.mkdirs();
            getLogger().info("Importing old portals from portals.yml");
            LegacyImporter.importPortals(this.portalServices);
        }

        // Check if config.yml exists and config.yaml doesn't exist
        File configFile = new File(this.getDataFolder(), "config.yml");
        File configYamlFile = new File(this.getDataFolder(), "config.yaml");
        if (configFile.exists() && !configYamlFile.exists()) {
            LegacyImporter.importConfig(this.configRepo);
        }
    }

    private void registerPlaceholderAPI() {
        if (this.getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            AdvancedPortalsCore.getInstance().getTagRegistry().registerTag(new ConditionsTag());
        }
    }

    private void checkVault() {
        if (this.getServer().getPluginManager().getPlugin("Vault") == null) {
            return;
        }
        RegisteredServiceProvider<Economy> economyProvider =
                Bukkit.getServicesManager().getRegistration(Economy.class);

        if (economyProvider == null) {
            return;
        }
        AdvancedPortalsCore.getInstance().getTagRegistry().registerTag(new CostTag(economyProvider.getProvider()));
    }
}
