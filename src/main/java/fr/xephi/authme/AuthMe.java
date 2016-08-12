package fr.xephi.authme;

import ch.jalu.injector.Injector;
import ch.jalu.injector.InjectorBuilder;
import com.google.common.annotations.VisibleForTesting;
import fr.xephi.authme.api.API;
import fr.xephi.authme.api.NewAPI;
import fr.xephi.authme.cache.auth.PlayerAuth;
import fr.xephi.authme.cache.auth.PlayerCache;
import fr.xephi.authme.cache.backup.PlayerDataStorage;
import fr.xephi.authme.cache.limbo.LimboCache;
import fr.xephi.authme.command.CommandHandler;
import fr.xephi.authme.datasource.DataSource;
import fr.xephi.authme.hooks.PluginHooks;
import fr.xephi.authme.initialization.DataFolder;
import fr.xephi.authme.initialization.Initializer;
import fr.xephi.authme.initialization.MetricsManager;
import fr.xephi.authme.listener.BlockListener;
import fr.xephi.authme.listener.EntityListener;
import fr.xephi.authme.listener.PlayerListener;
import fr.xephi.authme.listener.PlayerListener16;
import fr.xephi.authme.listener.PlayerListener18;
import fr.xephi.authme.listener.PlayerListener19;
import fr.xephi.authme.listener.ServerListener;
import fr.xephi.authme.output.Messages;
import fr.xephi.authme.permission.PermissionsManager;
import fr.xephi.authme.permission.PermissionsSystemType;
import fr.xephi.authme.process.Management;
import fr.xephi.authme.security.crypts.SHA256;
import fr.xephi.authme.settings.Settings;
import fr.xephi.authme.settings.SpawnLoader;
import fr.xephi.authme.settings.properties.PluginSettings;
import fr.xephi.authme.settings.properties.RestrictionSettings;
import fr.xephi.authme.settings.properties.SecuritySettings;
import fr.xephi.authme.task.CleanupTask;
import fr.xephi.authme.task.purge.PurgeService;
import fr.xephi.authme.util.BukkitService;
import fr.xephi.authme.util.GeoLiteAPI;
import fr.xephi.authme.util.MigrationService;
import fr.xephi.authme.util.Utils;
import fr.xephi.authme.util.ValidationService;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginLoader;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitWorker;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Level;

import static fr.xephi.authme.util.BukkitService.TICKS_PER_MINUTE;
import static fr.xephi.authme.util.Utils.isClassLoaded;

/**
 * The AuthMe main class.
 */
public class AuthMe extends JavaPlugin {

    // Constants
    private static final String PLUGIN_NAME = "AuthMeReloaded";
    private static final String LOG_FILENAME = "authme.log";
    private static final int CLEANUP_INTERVAL = 5 * TICKS_PER_MINUTE;

    // Default version and build number values;
    private static String pluginVersion = "N/D";
    private static String pluginBuildNumber = "Unknown";

    /*
     * Private instances
     */
    private Management management;
    private CommandHandler commandHandler;
    private PermissionsManager permsMan;
    private Settings settings;
    private Messages messages;
    private DataSource database;
    private PluginHooks pluginHooks;
    private SpawnLoader spawnLoader;
    private BukkitService bukkitService;
    private Injector injector;
    private GeoLiteAPI geoLiteApi;
    private PlayerCache playerCache;

    /**
     * Constructor.
     */
    public AuthMe() {
    }

    /*
     * Constructor for unit testing.
     */
    @VisibleForTesting
    @SuppressWarnings("deprecation") // the super constructor is deprecated to mark it for unit testing only
    protected AuthMe(final PluginLoader loader, final Server server, final PluginDescriptionFile description,
                     final File dataFolder, final File file) {
        super(loader, server, description, dataFolder, file);
    }

    /**
     * Get the plugin's name.
     *
     * @return The plugin's name.
     */
    public static String getPluginName() {
        return PLUGIN_NAME;
    }

    /**
     * Get the plugin's version.
     *
     * @return The plugin's version.
     */
    public static String getPluginVersion() {
        return pluginVersion;
    }

    /**
     * Get the plugin's build number.
     *
     * @return The plugin's build number.
     */
    public static String getPluginBuildNumber() {
        return pluginBuildNumber;
    }

    /**
     * Method called when the server enables the plugin.
     */
    @Override
    public void onEnable() {
        try {
            initializeServices();
        } catch (Exception e) {
            ConsoleLogger.logException("Aborting initialization of AuthMe:", e);
            stopOrUnload();
        }

        // Show settings warnings
        showSettingsWarnings();

        // If server is using PermissionsBukkit, print a warning that some features may not be supported
        if (PermissionsSystemType.PERMISSIONS_BUKKIT.equals(permsMan.getPermissionSystem())) {
            ConsoleLogger.warning("Warning! This server uses PermissionsBukkit for permissions. Some permissions features may not be supported!");
        }

        // Do a backup on start
        new PerformBackup(this, settings).doBackup(PerformBackup.BackupCause.START);

        // Set up Metrics
        MetricsManager.sendMetrics(this, settings);

        // Sponsor messages
        ConsoleLogger.info("Development builds are available on our jenkins, thanks to f14stelt.");
        ConsoleLogger.info("Do you want a good game server? Look at our sponsor GameHosting.it leader in Italy as Game Server Provider!");

        // Successful message
        ConsoleLogger.info("AuthMe " + getPluginVersion() + " build n°" + getPluginBuildNumber() + " correctly enabled!");

        // Purge on start if enabled
        PurgeService purgeService = injector.getSingleton(PurgeService.class);
        purgeService.runAutoPurge();

        // Schedule clean up task
        CleanupTask cleanupTask = injector.getSingleton(CleanupTask.class);
        cleanupTask.runTaskTimerAsynchronously(this, CLEANUP_INTERVAL, CLEANUP_INTERVAL);
    }

    private void initializeServices() throws Exception {
        // Set the plugin instance and load plugin info from the plugin description.
        loadPluginInfo();

        // Set the Logger instance and log file path
        ConsoleLogger.setLogger(getLogger());
        ConsoleLogger.setLogFile(new File(getDataFolder(), LOG_FILENAME));

        bukkitService = new BukkitService(this);
        Initializer initializer = new Initializer(this, bukkitService);

        // Load settings and set up the console and console filter
        settings = initializer.createSettings();
        ConsoleLogger.setLoggingOptions(settings);
        initializer.setupConsoleFilter(settings, getLogger());

        // Connect to the database and set up tables
        database = initializer.setupDatabase(settings);

        // Convert deprecated PLAINTEXT hash entries
        MigrationService.changePlainTextToSha256(settings, database, new SHA256());

        // Injector initialization
        injector = new InjectorBuilder().addDefaultHandlers("fr.xephi.authme").create();

        // Register elements of the Bukkit / JavaPlugin environment
        injector.register(AuthMe.class, this);
        injector.register(Server.class, getServer());
        injector.register(PluginManager.class, getServer().getPluginManager());
        injector.register(BukkitScheduler.class, getServer().getScheduler());
        injector.provide(DataFolder.class, getDataFolder());

        // Register elements we instantiate manually
        injector.register(Settings.class, settings);
        injector.register(DataSource.class, database);
        injector.register(BukkitService.class, bukkitService);

        instantiateServices(injector);

        // Reload support hook
        reloadSupportHook();

        // Register event listeners
        registerEventListeners(injector);

        // Start Email recall task if needed
        initializer.scheduleRecallEmailTask(settings, database, messages);
    }

    // Get version and build number of the plugin
    private void loadPluginInfo() {
        String versionRaw = this.getDescription().getVersion();
        int index = versionRaw.lastIndexOf("-");
        if (index != -1) {
            pluginVersion = versionRaw.substring(0, index);
            pluginBuildNumber = versionRaw.substring(index + 1);
            if (pluginBuildNumber.startsWith("b")) {
                pluginBuildNumber = pluginBuildNumber.substring(1);
            }
        }
    }

    protected void instantiateServices(Injector injector) {
        // PlayerCache is still injected statically sometimes
        playerCache = PlayerCache.getInstance();
        injector.register(PlayerCache.class, playerCache);

        messages = injector.getSingleton(Messages.class);
        permsMan = injector.getSingleton(PermissionsManager.class);
        bukkitService = injector.getSingleton(BukkitService.class);
        pluginHooks = injector.getSingleton(PluginHooks.class);
        spawnLoader = injector.getSingleton(SpawnLoader.class);
        commandHandler = injector.getSingleton(CommandHandler.class);
        management = injector.getSingleton(Management.class);
        geoLiteApi = injector.getSingleton(GeoLiteAPI.class);

        // Trigger construction of API classes; they will keep track of the singleton
        injector.getSingleton(NewAPI.class);
        injector.getSingleton(API.class);
    }

    /**
     * Show the settings warnings, for various risky settings.
     */
    private void showSettingsWarnings() {
        // Force single session disabled
        if (!settings.getProperty(RestrictionSettings.FORCE_SINGLE_SESSION)) {
            ConsoleLogger.warning("WARNING!!! By disabling ForceSingleSession, your server protection is inadequate!");
        }

        // Session timeout disabled
        if (settings.getProperty(PluginSettings.SESSIONS_TIMEOUT) == 0
            && settings.getProperty(PluginSettings.SESSIONS_ENABLED)) {
            ConsoleLogger.warning("WARNING!!! You set session timeout to 0, this may cause security issues!");
        }
    }

    /**
     * Register all event listeners.
     */
    protected void registerEventListeners(Injector injector) {
        // Get the plugin manager instance
        PluginManager pluginManager = getServer().getPluginManager();

        // Register event listeners
        pluginManager.registerEvents(injector.getSingleton(PlayerListener.class), this);
        pluginManager.registerEvents(injector.getSingleton(BlockListener.class), this);
        pluginManager.registerEvents(injector.getSingleton(EntityListener.class), this);
        pluginManager.registerEvents(injector.getSingleton(ServerListener.class), this);

        // Try to register 1.6 player listeners
        if (isClassLoaded("org.bukkit.event.player.PlayerEditBookEvent")) {
            pluginManager.registerEvents(injector.getSingleton(PlayerListener16.class), this);
        }

        // Try to register 1.8 player listeners
        if (isClassLoaded("org.bukkit.event.player.PlayerInteractAtEntityEvent")) {
            pluginManager.registerEvents(injector.getSingleton(PlayerListener18.class), this);
        }

        // Try to register 1.9 player listeners
        if (isClassLoaded("org.bukkit.event.player.PlayerSwapHandItemsEvent")) {
            pluginManager.registerEvents(injector.getSingleton(PlayerListener19.class), this);
        }
    }

    // Stop/unload the server/plugin as defined in the configuration
    public void stopOrUnload() {
        if (settings == null || settings.getProperty(SecuritySettings.STOP_SERVER_ON_PROBLEM)) {
            ConsoleLogger.warning("THE SERVER IS GOING TO SHUT DOWN AS DEFINED IN THE CONFIGURATION!");
            setEnabled(false);
            getServer().shutdown();
        } else {
            setEnabled(false);
        }
    }

    // TODO: check this, do we really need it? -sgdc3
    private void reloadSupportHook() {
        if (database != null) {
            int playersOnline = bukkitService.getOnlinePlayers().size();
            if (playersOnline == 0) {
                database.purgeLogged();
            } else if (settings.getProperty(SecuritySettings.USE_RELOAD_COMMAND_SUPPORT)) {
                for (PlayerAuth auth : database.getLoggedPlayers()) {
                    if (auth != null) {
                        auth.setLastLogin(new Date().getTime());
                        database.updateSession(auth);
                        playerCache.addPlayer(auth);
                    }
                }
            }
        }
    }

    @Override
    public void onDisable() {
        // Save player data
        BukkitService bukkitService = injector.getIfAvailable(BukkitService.class);
        LimboCache limboCache = injector.getIfAvailable(LimboCache.class);
        ValidationService validationService = injector.getIfAvailable(ValidationService.class);

        if (bukkitService != null && limboCache != null && validationService != null) {
            Collection<? extends Player> players = bukkitService.getOnlinePlayers();
            for (Player player : players) {
                savePlayer(player, limboCache, validationService);
            }
        }

        // Do backup on stop if enabled
        if (settings != null) {
            new PerformBackup(this, settings).doBackup(PerformBackup.BackupCause.STOP);
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                List<Integer> pendingTasks = new ArrayList<>();
                //returns only the async takss
                for (BukkitWorker pendingTask : getServer().getScheduler().getActiveWorkers()) {
                    if (pendingTask.getOwner().equals(AuthMe.this)
                        //it's not a peridic task
                        && !getServer().getScheduler().isQueued(pendingTask.getTaskId())) {
                        pendingTasks.add(pendingTask.getTaskId());
                    }
                }

                getLogger().log(Level.INFO, "Waiting for {0} tasks to finish", pendingTasks.size());
                int progress = 0;

                //one minute + some time checking the running state
                int tries = 60;
                while (!pendingTasks.isEmpty()) {
                    if (tries <= 0) {
                        getLogger().log(Level.INFO, "Async tasks times out after to many tries {0}", pendingTasks);
                        break;
                    }

                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                        break;
                    }

                    for (Iterator<Integer> iterator = pendingTasks.iterator(); iterator.hasNext(); ) {
                        int taskId = iterator.next();
                        if (!getServer().getScheduler().isCurrentlyRunning(taskId)) {
                            iterator.remove();
                            progress++;
                            getLogger().log(Level.INFO, "Progress: {0} / {1}", new Object[]{progress, pendingTasks.size()});
                        }
                    }

                    tries--;
                }

                if (database != null) {
                    database.close();
                }
            }
        }, "AuthMe-DataSource#close").start();

        // Disabled correctly
        ConsoleLogger.info("AuthMe " + this.getDescription().getVersion() + " disabled!");
        ConsoleLogger.close();
    }

    // Save Player Data
    private void savePlayer(Player player, LimboCache limboCache, ValidationService validationService) {
        final String name = player.getName().toLowerCase();
        if (safeIsNpc(player) || validationService.isUnrestricted(name)) {
            return;
        }
        if (limboCache.hasPlayerData(name)) {
            limboCache.restoreData(player);
            limboCache.removeFromCache(player);
        } else {
            if (settings.getProperty(RestrictionSettings.SAVE_QUIT_LOCATION)) {
                Location loc = spawnLoader.getPlayerLocationOrSpawn(player);
                final PlayerAuth auth = PlayerAuth.builder()
                    .name(player.getName().toLowerCase())
                    .realName(player.getName())
                    .location(loc).build();
                database.updateQuitLoc(auth);
            }
            if (settings.getProperty(RestrictionSettings.TELEPORT_UNAUTHED_TO_SPAWN)
                && !settings.getProperty(RestrictionSettings.NO_TELEPORT)) {
                PlayerDataStorage playerDataStorage = injector.getIfAvailable(PlayerDataStorage.class);
                if (playerDataStorage != null && !playerDataStorage.hasData(player)) {
                    playerDataStorage.saveData(player);
                }
            }
        }
        playerCache.removePlayer(name);
    }

    private boolean safeIsNpc(Player player) {
        return pluginHooks != null && pluginHooks.isNpc(player) || player.hasMetadata("NPC");
    }

    public String replaceAllInfo(String message, Player player) {
        String playersOnline = Integer.toString(bukkitService.getOnlinePlayers().size());
        String ipAddress = Utils.getPlayerIp(player);
        Server server = getServer();
        return message
            .replace("&", "\u00a7")
            .replace("{PLAYER}", player.getName())
            .replace("{ONLINE}", playersOnline)
            .replace("{MAXPLAYERS}", Integer.toString(server.getMaxPlayers()))
            .replace("{IP}", ipAddress)
            .replace("{LOGINS}", Integer.toString(playerCache.getLogged()))
            .replace("{WORLD}", player.getWorld().getName())
            .replace("{SERVER}", server.getServerName())
            .replace("{VERSION}", server.getBukkitVersion())
            // TODO: We should cache info like this, maybe with a class that extends Player?
            .replace("{COUNTRY}", geoLiteApi.getCountryName(ipAddress));
    }


    /**
     * Handle Bukkit commands.
     *
     * @param sender       The command sender (Bukkit).
     * @param cmd          The command (Bukkit).
     * @param commandLabel The command label (Bukkit).
     * @param args         The command arguments (Bukkit).
     *
     * @return True if the command was executed, false otherwise.
     */
    @Override
    public boolean onCommand(CommandSender sender, Command cmd,
                             String commandLabel, String[] args) {
        // Make sure the command handler has been initialized
        if (commandHandler == null) {
            getLogger().severe("AuthMe command handler is not available");
            return false;
        }

        // Handle the command
        return commandHandler.processCommand(sender, commandLabel, args);
    }

    // -------------
    // Service getters (deprecated)
    // Use @Inject fields instead
    // -------------

    /**
     * @return process manager
     *
     * @deprecated should be used in API classes only (temporarily)
     */
    @Deprecated
    public Management getManagement() {
        return management;
    }
}
