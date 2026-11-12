package pl.dzik.pojazdy;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.*;

public final class DzikPojazdy extends JavaPlugin implements Listener, CommandExecutor {

    private NamespacedKey typeKey;
    private NamespacedKey tierKey;
    private final Map<UUID, Inventory> planeInventories = new HashMap<>();
    private final Map<UUID, Long> drillCooldowns = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        typeKey = new NamespacedKey(this, "vehicle_type");
        tierKey = new NamespacedKey(this, "vehicle_tier");

        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("vehicle") != null) {
            getCommand("vehicle").setExecutor(this);
        }

        startVehicleLoop();
        getLogger().info("DzikPojazdy 1.21.4 wlaczony!");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) return true;

        if (args.length < 2) {
            player.sendMessage("§cUzycie: /vehicle <traktor|wiertlo|samolot|helikopter|statek> <zwykly|epicki|legendarny>");
            return true;
        }

        String type = args[0].toLowerCase();
        String tier = args[1].toLowerCase();
        ItemStack item = createVehicleItem(type, tier);

        if (item == null) {
            player.sendMessage("§cBledny typ lub rzadkosc!");
            return true;
        }

        player.getInventory().addItem(item);
        player.sendMessage("§aOtrzymales: " + item.getItemMeta().getDisplayName());
        return true;
    }

    private ItemStack createVehicleItem(String type, String tier) {
        int cmd = getCustomModelData(type, tier);
        if (cmd == -1) return null;

        ItemStack item = new ItemStack(Material.SADDLE);
        ItemMeta meta = item.getItemMeta();
        String color = tier.equals("legendarny") ? "§6§l" : (tier.equals("epicki") ? "§5§l" : "§7");

        meta.setDisplayName(color + capitalize(tier) + " " + capitalize(type));
        meta.setCustomModelData(cmd);
        meta.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, type);
        meta.getPersistentDataContainer().set(tierKey, PersistentDataType.STRING, tier);
        meta.setLore(List.of(
                "§ePPM na blok: §fPostaw pojazd",
                "§eW / S: §fJazda / Lot",
                "§eSpacja (Lotnictwo): §fWzbijanie sie w gore",
                "§eLPM (Samolot): §fZrzut TNT (Shift+PPM = schowek)"
        ));
        item.setItemMeta(meta);
        return item;
    }

    private int getCustomModelData(String type, String tier) {
        int base = switch (type) {
            case "traktor" -> 100;
            case "wiertlo" -> 200;
            case "samolot" -> 300;
            case "helikopter" -> 400;
            case "statek" -> 500;
            default -> -1;
        };
        if (base == -1) return -1;
        int offset = switch (tier) {
            case "zwykly" -> 1;
            case "epicki" -> 2;
            case "legendarny" -> 3;
            default -> -1;
        };
        return offset == -1 ? -1 : base + offset;
    }

    @EventHandler
    public void onPlaceVehicle(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getItem() == null) return;
        ItemMeta meta = event.getItem().getItemMeta();
        if (meta == null || !meta.getPersistentDataContainer().has(typeKey, PersistentDataType.STRING)) return;

        event.setCancelled(true);
        String type = meta.getPersistentDataContainer().get(typeKey, PersistentDataType.STRING);
        String tier = meta.getPersistentDataContainer().get(tierKey, PersistentDataType.STRING);

        Location loc = event.getClickedBlock().getLocation().add(0.5, 1.0, 0.5);
        EntityType entityType = type.equals("statek") ? EntityType.OAK_BOAT : EntityType.MINECART;
        Entity vehicle = loc.getWorld().spawnEntity(loc, entityType);

        vehicle.setCustomName(meta.getDisplayName());
        vehicle.setCustomNameVisible(true);
        vehicle.setGravity(!type.equals("samolot") && !type.equals("helikopter"));
        vehicle.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, type);
        vehicle.getPersistentDataContainer().set(tierKey, PersistentDataType.STRING, tier);

        if (type.equals("samolot")) {
            int slots = tier.equals("legendarny") ? 27 : (tier.equals("epicki") ? 18 : 9);
            planeInventories.put(vehicle.getUniqueId(), Bukkit.createInventory(null, slots, "§cSchowek TNT (" + capitalize(tier) + ")"));
        }

        event.getItem().setAmount(event.getItem().getAmount() - 1);
    }

    @EventHandler
    public void onInteractVehicle(PlayerInteractEntityEvent event) {
        Entity entity = event.getRightClicked();
        if (!entity.getPersistentDataContainer().has(typeKey, PersistentDataType.STRING)) return;

        String type = entity.getPersistentDataContainer().get(typeKey, PersistentDataType.STRING);
        String tier = entity.getPersistentDataContainer().get(tierKey, PersistentDataType.STRING);
        Player player = event.getPlayer();

        if (type.equals("samolot") && player.isSneaking()) {
            event.setCancelled(true);
            Inventory inv = planeInventories.get(entity.getUniqueId());
            if (inv != null) player.openInventory(inv);
            return;
        }

        if (type.equals("helikopter")) {
            int max = tier.equals("legendarny") ? 4 : (tier.equals("epicki") ? 3 : 2);
            if (entity.getPassengers().size() < max && !entity.getPassengers().contains(player)) {
                event.setCancelled(true);
                entity.addPassenger(player);
            }
        }
    }

    @EventHandler
    public void onDropTnt(PlayerInteractEvent event) {
        if (event.getAction() != Action.LEFT_CLICK_AIR && event.getAction() != Action.LEFT_CLICK_BLOCK) return;
        Player player = event.getPlayer();
        Entity vehicle = player.getVehicle();
        if (vehicle == null || !vehicle.getPersistentDataContainer().has(typeKey, PersistentDataType.STRING)) return;

        String type = vehicle.getPersistentDataContainer().get(typeKey, PersistentDataType.STRING);
        if (type.equals("samolot")) {
            Inventory inv = planeInventories.get(vehicle.getUniqueId());
            if (inv != null && inv.contains(Material.TNT)) {
                inv.removeItem(new ItemStack(Material.TNT, 1));
                TNTPrimed tnt = (TNTPrimed) vehicle.getWorld().spawnEntity(vehicle.getLocation().subtract(0, 1.2, 0), EntityType.TNT);
                tnt.setFuseTicks(45);
                if (!getConfig().getBoolean("tnt-destroys-blocks", true)) {
                    tnt.setYield(0F);
                }
                player.sendMessage("§c[Samolot] Zrzucono TNT!");
            } else {
                player.sendMessage("§7Brak TNT w schowku (kucnij i kliknij PPM na samolot).");
            }
        }
    }

    private void startVehicleLoop() {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (World world : Bukkit.getWorlds()) {
                    for (Entity entity : world.getEntities()) {
                        if (!entity.getPersistentDataContainer().has(typeKey, PersistentDataType.STRING)) continue;

                        String type = entity.getPersistentDataContainer().get(typeKey, PersistentDataType.STRING);
                        String tier = entity.getPersistentDataContainer().get(tierKey, PersistentDataType.STRING);

                        if (!entity.getPassengers().isEmpty() && entity.getPassengers().get(0) instanceof Player driver) {
                            Input input = driver.getCurrentInput();
                            double speed = getSpeed(type, tier);
                            Vector lookDir = driver.getLocation().getDirection();
                            lookDir.setY(0).normalize();

                            Vector velocity = new Vector(0, 0, 0);
                            if (input.isForward()) velocity.add(lookDir.clone().multiply(speed));
                            if (input.isBackward()) velocity.add(lookDir.clone().multiply(-speed * 0.6));

                            entity.setRotation(driver.getLocation().getYaw(), 0);

                            if (type.equals("samolot") || type.equals("helikopter")) {
                                if (input.isJump()) {
                                    velocity.setY(speed * 0.8);
                                } else if (type.equals("samolot") && input.isForward()) {
                                    velocity.setY(driver.getLocation().getDirection().getY() * speed);
                                } else {
                                    velocity.setY(-0.08);
                                }
                                entity.setVelocity(velocity);

                                if (type.equals("helikopter")) {
                                    int max = tier.equals("legendarny") ? 4 : (tier.equals("epicki") ? 3 : 2);
                                    if (entity.getPassengers().size() < max) {
                                        for (Entity near : entity.getNearbyEntities(2.5, 2.5, 2.5)) {
                                            if (near instanceof Villager && !entity.getPassengers().contains(near)) {
                                                entity.addPassenger(near);
                                                break;
                                            }
                                        }
                                    }
                                }
                            } else if (type.equals("statek")) {
                                if (entity.isInWater()) {
                                    velocity.setY(0.05);
                                    entity.setVelocity(velocity);
                                }
                            } else {
                                velocity.setY(entity.getVelocity().getY());
                                Block frontBlock = entity.getLocation().add(lookDir.clone().multiply(1.1)).getBlock();
                                if (frontBlock.getType().isSolid() && !frontBlock.getRelative(0, 1, 0).getType().isSolid()) {
                                    velocity.setY(0.45);
                                }
                                entity.setVelocity(velocity);
                            }

                            if (type.equals("traktor")) runTractor(entity, tier);
                            if (type.equals("wiertlo")) runDrill(entity, tier, lookDir);
                        }
                    }
                }
            }
        }.runTaskTimer(this, 1L, 1L);
    }

    private double getSpeed(String type, String tier) {
        double mult = tier.equals("legendarny") ? 1.8 : (tier.equals("epicki") ? 1.3 : 0.85);
        return switch (type) {
            case "samolot" -> 0.85 * mult;
            case "helikopter" -> 0.65 * mult;
            case "statek" -> 0.55 * mult;
            case "traktor" -> 0.35 * mult;
            case "wiertlo" -> 0.30 * mult;
            default -> 0.4;
        };
    }

    private void runTractor(Entity tractor, String tier) {
        int r = tier.equals("legendarny") ? 3 : (tier.equals("epicki") ? 2 : 1);
        Location center = tractor.getLocation();

        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                Block b = center.clone().add(x, 0, z).getBlock();
                if (b.getBlockData() instanceof Ageable ageable && ageable.getAge() == ageable.getMaximumAge()) {
                    Material cropType = b.getType();
                    b.breakNaturally();
                    b.setType(cropType);
                }
            }
        }
    }

    private void runDrill(Entity drill, String tier, Vector direction) {
        long delayMs = getConfig().getLong("drill-cooldown-ms." + tier, tier.equals("legendarny") ? 900L : (tier.equals("epicki") ? 1500L : 2400L));
        long now = System.currentTimeMillis();

        if (now - drillCooldowns.getOrDefault(drill.getUniqueId(), 0L) < delayMs) return;
        drillCooldowns.put(drill.getUniqueId(), now);

        Location front = drill.getLocation().add(direction.clone().multiply(1.6));
        for (int x = -1; x <= 1; x++) {
            for (int y = 0; y <= 2; y++) {
                for (int z = -1; z <= 1; z++) {
                    Block b = front.clone().add(x, y, z).getBlock();
                    if (b.getType() != Material.AIR && b.getType() != Material.BEDROCK && !b.isLiquid()) {
                        b.breakNaturally();
                    }
                }
            }
        }
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }
}
