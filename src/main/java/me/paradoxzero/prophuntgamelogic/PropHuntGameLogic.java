package me.paradoxzero.prophuntgamelogic;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

public final class PropHuntGameLogic extends JavaPlugin implements Listener {

    private boolean isLobbyRunning = false;
    private boolean isGameRunning = false;
    private Player seeker;
    private List<Player> hiders = new ArrayList<>();
    private long totalTime = 180; // Total game time in seconds
    private long timeRemaining = totalTime; // Time remaining in seconds
    private BukkitRunnable mainLoopTask;

    private final Map<Player, BlockDisplay> playerBlockDisplays = new HashMap<>();
    private final Map<Player, Location> lastLocations = new HashMap<>();
    private final Map<Player, Long> stationaryTime = new HashMap<>();
    private final Map<Player, BossBar> playerBossBars = new HashMap<>();

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        startMainLoop();


    }

    @Override
    public void onDisable() {
        stopMainLoop(); // Stop any ongoing loops when the plugin is disabled
    }

    private void startMainLoop() {
        stopMainLoop(); // Ensure no previous loop is running

        mainLoopTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (Bukkit.getOnlinePlayers().size() >= 2 && !isLobbyRunning && !isGameRunning) {
                    isLobbyRunning = true;
                    lobbyFunction();
                }

                if (isGameRunning) {
                    timeRemaining -= 1; // Decrement time remaining every second

                    // Update XP bar and level for each player
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        updatePlayerXP(player);
                    }

                    if (timeRemaining <= 0) {
                        endGame("Time's up! Seekers failed to catch all hiders.");
                        return;
                    }
                }
            }
        };
        mainLoopTask.runTaskTimer(this, 0, 20); // Run every second
    }

    private void stopMainLoop() {
        if (mainLoopTask != null && !mainLoopTask.isCancelled()) {
            mainLoopTask.cancel(); // Stop the main loop task
        }
        mainLoopTask = null;
    }

    private void updatePlayerXP(Player player) {
        // Set the XP level to the time remaining (seconds)
        player.setLevel((int) timeRemaining);

        // Set the XP bar to represent the percentage of time left
        float progress = (float) timeRemaining / totalTime;
        player.setExp(progress);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        player.teleport(new Location(player.getWorld(), 3.5, -42, 17.5)); // Spectator box spawn
        player.getInventory().clear(); // Wipe inventory
        player.setGameMode(GameMode.ADVENTURE); // Set to adventure mode
        for (PotionEffect effect : player.getActivePotionEffects()) {
            player.removePotionEffect(effect.getType());
        }

        if (isGameRunning) {
            player.sendMessage("The game is already running. You'll join the next round.");
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (hiders.contains(player)) {
            hiders.remove(player); // Remove the player from the hiders list
            if (hiders.isEmpty()) {
                endGame("Seekers wins! The last hider has left the game.");
            }
        }

        if (player == seeker) {
            endGame("Hiders win! The seeker has left the game.");
        }
    }

    @EventHandler
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player && event.getEntity() instanceof Player)) return;

        Player damager = (Player) event.getDamager();
        Player victim = (Player) event.getEntity();

        if (damager != seeker && !hiders.contains(damager)) {
            event.setCancelled(true); // Players not in teams cannot deal damage
        }

        if (victim != seeker && !hiders.contains(victim)) {
            event.setCancelled(true); // Players not in teams cannot be damaged
        }

        if (hiders.contains(damager)) {
            event.setCancelled(true); // Hiders cannot deal damage
        }
    }

    @EventHandler
    public void onPlayerDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player player = (Player) event.getEntity();
        hiders.remove(player);
        if (hiders.isEmpty()) {
            endGame("Seekers win! All hiders are dead.");
        }
    }

    public void lobbyFunction() {
        Bukkit.broadcastMessage("Game starts in 20 seconds!");
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.teleport(new Location(player.getWorld(), 3.5, -42, 17.5));
            player.setGameMode(GameMode.ADVENTURE);
            for (PotionEffect effect : player.getActivePotionEffects()) {
                player.removePotionEffect(effect.getType());
            }
        }

        new BukkitRunnable() {
            @Override
            public void run() {
                startGame();
            }
        }.runTaskLater(this, 20 * 20); // 20 seconds wait
    }

    public void startGame() {
        isLobbyRunning = false;
        isGameRunning = true;
        timeRemaining = totalTime; // Reset the remaining time

        List<Player> onlinePlayers = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (onlinePlayers.size() < 2) {
            Bukkit.broadcastMessage("Not enough players to start the game.");
            return;
        }

        Collections.shuffle(onlinePlayers); // Randomize players
        seeker = onlinePlayers.remove(0); // Select one player as the seeker
        hiders.clear();
        hiders.addAll(onlinePlayers); // Remaining players are hiders

        // Assign roles and prepare players
        seeker.getInventory().addItem(new ItemStack(Material.DIAMOND_SWORD)); // Give seeker a sword
        int blindnessDuration = 20 * 16; // 16 seconds for teleport and blindness
        seeker.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, blindnessDuration, 1)); // Blindness
        seeker.sendMessage("You are the Seeker!");

        for (Player hider : hiders) {
            startPlayerMovementCheck(hider);

            hider.sendMessage("You are a Hider!");

            // Add the code here for the prop transformation for hiders
            hider.addPotionEffect(new org.bukkit.potion.PotionEffect(PotionEffectType.INVISIBILITY, Integer.MAX_VALUE, 1, false, false));
            spawnBlockDisguise(hider);
            startBlockFollowTask(hider);

            BossBar bossBar = Bukkit.createBossBar("Stil staan om te teleporteren...", BarColor.BLUE, BarStyle.SEGMENTED_10);
            bossBar.setVisible(false);
            bossBar.addPlayer(hider);
            playerBossBars.put(hider, bossBar);
        }

        Bukkit.broadcastMessage("Hiders are hiding! Seekers will spawn in 15 seconds.");
        teleportHidersToGame();

        new BukkitRunnable() {
            @Override
            public void run() {
                teleportSeekerToGame();
            }
        }.runTaskLater(this, 15 * 20); // Wait 15 seconds before teleporting seeker
    }

    public void teleportHidersToGame() {
        for (Player player : hiders) {
            player.teleport(new Location(Bukkit.getWorlds().get(0), 3, -62, 2));
        }
    }

    public void teleportSeekerToGame() {
        if (seeker != null) {
            seeker.teleport(new Location(Bukkit.getWorlds().get(0), 3, -62, 2));
            Bukkit.broadcastMessage("The seeker has entered the game!");
        }
    }

    private void spawnBlockDisguise(Player player) {
        Material randomBlock = getRandomBlock();

        BlockDisplay blockDisplay = (BlockDisplay) player.getWorld().spawnEntity(player.getLocation(), EntityType.BLOCK_DISPLAY);
        blockDisplay.setBlock(randomBlock.createBlockData());
        blockDisplay.setInterpolationDuration(5);

        playerBlockDisplays.put(player, blockDisplay);
    }

    private Material getRandomBlock() {
        List<Material> blockTypes = Arrays.asList(
                Material.POTTED_OAK_SAPLING, Material.POTTED_SPRUCE_SAPLING, Material.POTTED_BIRCH_SAPLING,
                Material.POTTED_JUNGLE_SAPLING, Material.POTTED_ACACIA_SAPLING, Material.POTTED_DARK_OAK_SAPLING,
                Material.HAY_BLOCK, Material.PUMPKIN, Material.BEEHIVE, Material.MELON,
                Material.COMPOSTER, Material.BARREL, Material.HONEY_BLOCK, Material.BAMBOO_BLOCK
        );


        Random random = new Random();
        return blockTypes.get(random.nextInt(blockTypes.size()));
    }

    private void startBlockFollowTask(Player player) {
        new BukkitRunnable() {
            @Override
            public void run() {
                BlockDisplay blockDisplay = playerBlockDisplays.get(player);

                if (player.isOnline() && blockDisplay != null && !blockDisplay.isDead()) {
                    Location playerLocation = player.getLocation();

                    playerLocation.setPitch(0);
                    playerLocation.setYaw(0);

                    blockDisplay.teleport(playerLocation.add(-0.5, 0, -0.5));
                } else {
                    this.cancel();
                }
            }
        }.runTaskTimer(this, 0L, 1L);
    }

    private void startPlayerMovementCheck(Player player) {
        new BukkitRunnable() {
            @Override
            public void run() {
                    Location currentLocation = player.getLocation();
                    Location lastLocation = lastLocations.get(player);

                    if (lastLocation == null) {
                        lastLocations.put(player, currentLocation);
                        stationaryTime.put(player, System.currentTimeMillis());
                        return;
                    }

                    if (hasMoved(currentLocation, lastLocation)) {
                        lastLocations.put(player, currentLocation);
                        stationaryTime.put(player, System.currentTimeMillis());

                        BossBar bossBar = playerBossBars.get(player);
                        if (bossBar != null && bossBar.isVisible()) {
                            bossBar.setVisible(false); // Verberg de BossBar
                        }
                    } else {
                        // Controleer hoe lang de speler al stilstaat
                        long timeStationary = System.currentTimeMillis() - stationaryTime.get(player);

                        // Resterende tijd in seconden
                        // Als de speler stilstaat, toon de BossBar en update de voortgang
                        BossBar bossBar = playerBossBars.get(player);
                        if (timeStationary < 5000) {
                            if (bossBar != null && !bossBar.isVisible()) {
                                bossBar.setVisible(true); // Toon de BossBar
                            }

                            double progress = timeStationary / 5000.0; // Bereken voortgang (0.0 tot 1.0)
                            if (bossBar != null) {
                                bossBar.setProgress(progress); // Update de BossBar voortgang
                            }
                        }

                        // Als de speler 5 seconden stil staat en nog niet in het midden is geteleporteerd
                        if (timeStationary >= 5000 && !isCenteredInBlock(player)) {
                            // Teleporteer de speler naar het midden van het blok
                            Location blockCenter = currentLocation.getBlock().getLocation().add(0.5, 0, 0.5);
                            player.teleport(blockCenter);

                            if (bossBar != null) {
                                bossBar.setVisible(false);
                                bossBar.setProgress(0.0);
                            }

                            // Sla de nieuwe locatie op
                            lastLocations.put(player, blockCenter);
                        }
                    }
            }
        }.runTaskTimer(this, 0L, 20L); // Herhaal elke seconde
    }

    // Functie om te controleren of de speler al in het midden van het blok staat
    private boolean isCenteredInBlock(Player player) {
        Location location = player.getLocation();
        return location.getX() % 1 == 0.5 && location.getZ() % 1 == 0.5;
    }

    // Functie om te controleren of de speler is bewogen
    private boolean hasMoved(Location currentLocation, Location lastLocation) {
        return currentLocation.getX() != lastLocation.getX() ||
                // currentLocation.getY() != lastLocation.getY() ||
                currentLocation.getZ() != lastLocation.getZ();
    }

    public void endGame(String message) {
        isGameRunning = false;
        isLobbyRunning = false; // Reset lobby state
        Bukkit.broadcastMessage(message);

        for (Player player : Bukkit.getOnlinePlayers()) {
            player.teleport(new Location(Bukkit.getWorlds().get(0), 3.5, -42, 17.5)); // Spectator box
            player.getInventory().clear(); // Clear inventory
            player.setGameMode(GameMode.ADVENTURE);
            for (PotionEffect effect : player.getActivePotionEffects()) {
                player.removePotionEffect(effect.getType());
            }
        }

        for (BlockDisplay blockDisplay : playerBlockDisplays.values()) {
            if (blockDisplay != null) {
                blockDisplay.remove();
            }
        }
        playerBlockDisplays.clear();

        for (BossBar bossBar : playerBossBars.values()) {
            if (bossBar != null) {
                bossBar.removeAll();
            }
        }
        playerBossBars.clear();

        stopMainLoop(); // Stop the main loop to ensure a clean reset

        new BukkitRunnable() {
            @Override
            public void run() {
                startMainLoop(); // Restart the main loop cleanly
            }
        }.runTaskLater(this, 100); // Delay to ensure players reset before next game
    }
}
