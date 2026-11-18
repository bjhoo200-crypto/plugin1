package kr.example.cannon;

import io.papermc.paper.entity.TeleportFlag;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.entity.Shulker;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 함선(쾌속선 / 화물선).
 * - 물 위에서만 설치/이동 가능, 6인승, 운전자(1번 좌석)가 WASD 로 조종
 * - 화물선: 큰 상자 4개 분량(54칸 x 4) 화물칸
 * 좌표계: 배 중심(수면) 기준, +Z = 뱃머리, +X = 왼쪽, +Y = 위
 */
public final class ShipManager implements Listener {

    // =====================================================================
    //  모델 정의
    // =====================================================================
    /** 블럭 디스플레이 한 조각 (중심 좌표 + 크기 + 회전) */
    record Part(String data, float cx, float cy, float cz, float sx, float sy, float sz, float yaw, float pitch) {}

    /** pads: {x, z, 크기} - 갑판 위에 설 수 있게 하는 (보이지 않는) 바닥판들 */
    record ShipType(String key, String label, String iconModel,
                    float[][] seats, float[][] holds, float[][] foot, float[][] pads,
                    float sternZ, float bowZ, float halfWidth, float hullWidth, float hullHeight,
                    List<Part> parts, double defMax, double defAccel, double defTurn) {}

    static Part box(String data, float cx, float cy, float cz, float sx, float sy, float sz) {
        return new Part(data, cx, cy, cz, sx, sy, sz, 0f, 0f);
    }

    static Part tilt(String data, float cx, float cy, float cz, float sx, float sy, float sz, float pitchDeg) {
        return new Part(data, cx, cy, cz, sx, sy, sz, 0f, (float) Math.toRadians(pitchDeg));
    }

    /** (x1,z1) -> (x2,z2) 를 잇는 벽 */
    static Part wall(String data, float x1, float z1, float x2, float z2, float cy, float h, float th) {
        float dx = x2 - x1;
        float dz = z2 - z1;
        float len = (float) Math.sqrt(dx * dx + dz * dz);
        return new Part(data, (x1 + x2) / 2f, cy, (z1 + z2) / 2f, th, h, len + 0.06f,
                (float) Math.atan2(dx, dz), 0f);
    }

    /** 선체: 바닥 + 갑판 + 양옆 벽 + 난간 + 고물 + 뾰족한 이물 */
    static void hull(List<Part> o, String hullMat, String deckMat, String trimMat,
                     float hw, float zs, float zt, float zb) {
        float mid = (zs + zt) / 2f;
        float len = zt - zs;
        o.add(box(hullMat, 0f, -0.32f, mid, 2 * hw - 0.2f, 0.14f, len));
        o.add(box(deckMat, 0f, -0.23f, mid, 2 * hw - 0.4f, 0.04f, len - 0.2f));
        for (int sd = -1; sd <= 1; sd += 2) {
            o.add(box(hullMat, sd * (hw - 0.1f), 0.05f, mid, 0.2f, 0.75f, len));
            o.add(box(trimMat, sd * (hw - 0.1f), 0.47f, mid, 0.26f, 0.1f, len));
            o.add(wall(hullMat, sd * (hw - 0.1f), zt, sd * 0.05f, zb, 0.05f, 0.75f, 0.2f));
            o.add(wall(trimMat, sd * (hw - 0.1f), zt, sd * 0.05f, zb, 0.47f, 0.1f, 0.26f));
        }
        o.add(box(hullMat, 0f, 0.05f, zs + 0.1f, 2 * hw, 0.75f, 0.2f));
        int n = 3;
        for (int k = 0; k < n; k++) {
            float a = zt + (zb - zt) * k / n;
            float b = zt + (zb - zt) * (k + 1) / n;
            float t = (k + 0.5f) / n;
            float w = 2 * (hw - 0.1f) * (1f - t) + 0.1f;
            float zc = (a + b) / 2f;
            float l = (b - a) + 0.05f;
            o.add(box(hullMat, 0f, -0.32f, zc, w, 0.14f, l));
            o.add(box(deckMat, 0f, -0.23f, zc, Math.max(0.1f, w - 0.2f), 0.04f, l));
        }
    }

    static void seatVisuals(List<Part> o, float[][] seats, String mat) {
        for (float[] st : seats) {
            o.add(box(mat, st[0], -0.14f, st[1], 0.5f, 0.12f, 0.5f));
            o.add(box(mat, st[0], 0.12f, st[1] - 0.27f, 0.5f, 0.42f, 0.08f));
        }
    }

    static ShipType fastType() {
        List<Part> p = new ArrayList<>();
        float[][] seats = {{0f, 1.0f}, {-0.45f, 0.0f}, {0.45f, 0.0f}, {-0.45f, -1.0f}, {0.45f, -1.0f}, {0f, -1.7f}};
        hull(p, "minecraft:white_concrete", "minecraft:spruce_planks", "minecraft:red_concrete",
                1.0f, -2.3f, 1.9f, 3.1f);
        seatVisuals(p, seats, "minecraft:gray_concrete");
        // 계기판 + 앞유리
        p.add(box("minecraft:black_concrete", 0f, 0.12f, 1.5f, 0.8f, 0.3f, 0.3f));
        p.add(box("minecraft:red_concrete", 0f, 0.3f, 1.5f, 0.3f, 0.06f, 0.2f));
        p.add(tilt("minecraft:light_blue_stained_glass", 0f, 0.65f, 1.7f, 1.7f, 0.75f, 0.05f, -25f));
        // 엔진 + 후미 날개
        p.add(box("minecraft:iron_block", 0f, 0.12f, -2.5f, 0.9f, 0.6f, 0.5f));
        p.add(box("minecraft:black_concrete", 0f, -0.45f, -2.55f, 0.2f, 0.6f, 0.2f));
        p.add(box("minecraft:red_concrete", 0f, 0.65f, -2.3f, 1.7f, 0.08f, 0.5f));
        p.add(box("minecraft:black_concrete", -0.7f, 0.45f, -2.3f, 0.08f, 0.3f, 0.08f));
        p.add(box("minecraft:black_concrete", 0.7f, 0.45f, -2.3f, 0.08f, 0.3f, 0.08f));
        float[][] foot = {{0f, 3.0f}, {0.5f, 2.4f}, {-0.5f, 2.4f}, {1.0f, 1.2f}, {-1.0f, 1.2f},
                {1.0f, -0.5f}, {-1.0f, -0.5f}, {1.0f, -2.1f}, {-1.0f, -2.1f}, {0f, -2.6f}, {0f, 0f}};
        float[][] pads = {{0f, -1.3f, 2.0f}, {0f, 0.0f, 2.0f}, {0f, 0.9f, 2.0f}, {0f, 2.2f, 1.2f}, {0f, 2.7f, 0.6f}};
        return new ShipType("fast", "쾌속선", "minecraft:birch_boat", seats, new float[0][], foot, pads,
                -2.3f, 3.1f, 1.0f, 1.4f, 1.0f, List.copyOf(p), 1.0, 0.04, 3.4);
    }

    static ShipType cargoType() {
        List<Part> p = new ArrayList<>();
        float[][] seats = {{0f, 1.9f}, {-0.75f, 1.1f}, {0f, 1.1f}, {0.75f, 1.1f}, {-0.4f, 0.3f}, {0.4f, 0.3f}};
        float[][] holds = {{-0.65f, -0.9f}, {0.65f, -0.9f}, {-0.65f, -1.9f}, {0.65f, -1.9f}};
        hull(p, "minecraft:gray_concrete", "minecraft:dark_oak_planks", "minecraft:orange_concrete",
                1.25f, -3.2f, 2.4f, 3.6f);
        seatVisuals(p, seats, "minecraft:black_concrete");
        // 계기판 + 앞유리
        p.add(box("minecraft:black_concrete", 0f, 0.12f, 2.3f, 1.0f, 0.3f, 0.3f));
        p.add(tilt("minecraft:light_blue_stained_glass", 0f, 0.65f, 2.4f, 2.0f, 0.7f, 0.05f, -20f));
        // 화물 상자 (화물칸 4개와 같은 위치)
        for (float[] h : holds) {
            p.add(box("minecraft:barrel[facing=up]", h[0], 0.2f, h[1], 0.95f, 0.8f, 0.95f));
        }
        // 선실 + 창 + 굴뚝
        p.add(box("minecraft:white_concrete", 0f, 0.62f, -2.8f, 2.0f, 1.4f, 0.7f));
        p.add(box("minecraft:light_blue_stained_glass", -0.5f, 0.85f, -2.44f, 0.6f, 0.4f, 0.04f));
        p.add(box("minecraft:light_blue_stained_glass", 0.5f, 0.85f, -2.44f, 0.6f, 0.4f, 0.04f));
        p.add(box("minecraft:red_concrete", 0.55f, 1.65f, -3.0f, 0.45f, 0.5f, 0.45f));
        p.add(box("minecraft:black_concrete", 0.55f, 1.98f, -3.0f, 0.5f, 0.1f, 0.5f));
        float[][] foot = {{0f, 3.6f}, {0.8f, 2.8f}, {-0.8f, 2.8f}, {1.25f, 1.5f}, {-1.25f, 1.5f},
                {1.25f, 0f}, {-1.25f, 0f}, {1.25f, -1.6f}, {-1.25f, -1.6f}, {1.25f, -3.0f}, {-1.25f, -3.0f},
                {0f, -3.2f}, {0f, 0f}};
        float[][] pads = {{0f, -1.2f, 2.4f}, {0f, 0.9f, 2.4f}, {0f, 2.7f, 1.4f}, {0f, 3.3f, 0.8f}};
        return new ShipType("cargo", "화물선", "minecraft:dark_oak_chest_boat", seats, holds, foot, pads,
                -3.2f, 3.6f, 1.25f, 2.0f, 1.0f, List.copyOf(p), 0.6, 0.02, 2.2);
    }

    static Transformation transformOf(Part p) {
        Quaternionf rot = new Quaternionf().rotationYXZ(p.yaw(), p.pitch(), 0f);
        Vector3f half = new Vector3f(p.sx() / 2f, p.sy() / 2f, p.sz() / 2f);
        Vector3f tr = new Vector3f(p.cx(), p.cy(), p.cz()).sub(rot.transform(half, new Vector3f()));
        return new Transformation(tr, rot, new Vector3f(p.sx(), p.sy(), p.sz()), new Quaternionf());
    }

    // =====================================================================
    //  필드
    // =====================================================================
    /** 배를 수면보다 살짝 띄워서(갑판이 물 위로 나오게) 걸어 다닐 때 물에 안 잠기게 함 */
    static final double LIFT = 0.3;
    /** 갑판 윗면 높이(배 중심 기준) */
    static final double DECK_TOP = -0.21;
    static final double ANCHOR_DY = -0.4;
    static final double SEAT_DY = -0.05;

    final CannonPlugin plugin;
    final Map<String, ShipType> types = new LinkedHashMap<>();
    final Map<String, double[]> tune = new HashMap<>(); // key -> {maxSpeed, accel, turn}
    final Map<UUID, Ship> ships = new HashMap<>();
    double drag = 0.97;
    long tickCount = 0;

    NamespacedKey K_ID, K_ROLE, K_IDX, K_TYPE, K_YAW, K_ITEM, K_FUEL;
    double fuelTicksPerLiter = 1200; // 1분에 1L
    NamespacedKey[] K_CARGO;

    ShipManager(CannonPlugin plugin) {
        this.plugin = plugin;
    }

    void enable() {
        K_ID = new NamespacedKey(plugin, "ship_id");
        K_ROLE = new NamespacedKey(plugin, "ship_role");
        K_IDX = new NamespacedKey(plugin, "ship_idx");
        K_TYPE = new NamespacedKey(plugin, "ship_type");
        K_YAW = new NamespacedKey(plugin, "ship_yaw");
        K_ITEM = new NamespacedKey(plugin, "ship_item");
        K_FUEL = new NamespacedKey(plugin, "ship_fuel");
        K_CARGO = new NamespacedKey[]{
                new NamespacedKey(plugin, "ship_cargo_0"), new NamespacedKey(plugin, "ship_cargo_1"),
                new NamespacedKey(plugin, "ship_cargo_2"), new NamespacedKey(plugin, "ship_cargo_3")};

        types.put("fast", fastType());
        types.put("cargo", cargoType());
        FileConfiguration c = plugin.getConfig();
        drag = c.getDouble("ship.drag", 0.97);
        fuelTicksPerLiter = Math.max(1, c.getDouble("ship.fuel-seconds-per-liter", 60) * 20);
        for (ShipType t : types.values()) {
            tune.put(t.key(), new double[]{
                    c.getDouble("ship." + t.key() + ".max-speed", t.defMax()),
                    c.getDouble("ship." + t.key() + ".accel", t.defAccel()),
                    c.getDouble("ship." + t.key() + ".turn", t.defTurn())});
        }

        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);

        // 이미 로드된 청크의 함선 복구
        for (World w : Bukkit.getWorlds()) {
            for (Interaction hb : w.getEntitiesByClass(Interaction.class)) {
                tryRegister(hb, 0);
            }
        }
    }

    void disable() {
        for (Ship s : ships.values()) s.saveState();
        ships.clear();
    }

    // =====================================================================
    //  유틸
    // =====================================================================
    void tag(Entity en, String sid, String role, int idx) {
        PersistentDataContainer p = en.getPersistentDataContainer();
        p.set(K_ID, PersistentDataType.STRING, sid);
        p.set(K_ROLE, PersistentDataType.STRING, role);
        p.set(K_IDX, PersistentDataType.INTEGER, idx);
    }

    String role(Entity en) {
        return en.getPersistentDataContainer().get(K_ROLE, PersistentDataType.STRING);
    }

    int idxOf(Entity en) {
        Integer i = en.getPersistentDataContainer().get(K_IDX, PersistentDataType.INTEGER);
        return i == null ? 0 : i;
    }

    boolean isShipEntity(Entity en) {
        return en.getPersistentDataContainer().has(K_ID, PersistentDataType.STRING);
    }

    Ship shipOf(Entity en) {
        String sid = en.getPersistentDataContainer().get(K_ID, PersistentDataType.STRING);
        if (sid == null) return null;
        try {
            return ships.get(UUID.fromString(sid));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    static boolean isWater(Block b) {
        Material t = b.getType();
        if (t == Material.WATER || t == Material.BUBBLE_COLUMN || t == Material.KELP || t == Material.KELP_PLANT
                || t == Material.SEAGRASS || t == Material.TALL_SEAGRASS) return true;
        return b.getBlockData() instanceof Waterlogged w && w.isWaterlogged();
    }

    static boolean clear(Block b) {
        return b.isPassable() || b.getType() == Material.LILY_PAD;
    }

    static double surfaceHeight(Block b) {
        if (b.getType() == Material.WATER && b.getBlockData() instanceof Levelled lv) {
            int l = lv.getLevel();
            return l >= 8 ? 1.0 : (8 - l) / 9.0;
        }
        return 0.89;
    }

    /** 이 자리에 배가 놓일 수 있는가: 발자국 전체가 물 위이고, 위쪽이 비어 있어야 함 */
    static boolean canOccupy(World w, ShipType t, double cx, double cy, double cz, float yaw) {
        double rad = Math.toRadians(yaw);
        double cs = Math.cos(rad);
        double sn = Math.sin(rad);
        double sy = cy - LIFT; // 수면 높이
        for (float[] f : t.foot()) {
            double wx = cx + f[0] * cs - f[1] * sn;
            double wz = cz + f[0] * sn + f[1] * cs;
            int bx = (int) Math.floor(wx);
            int bz = (int) Math.floor(wz);
            if (!w.isChunkLoaded(bx >> 4, bz >> 4)) return false;
            if (!isWater(w.getBlockAt(bx, (int) Math.floor(sy - 0.35), bz))) return false;
            if (!clear(w.getBlockAt(bx, (int) Math.floor(cy + 0.1), bz))) return false;
            if (!clear(w.getBlockAt(bx, (int) Math.floor(cy + 1.6), bz))) return false;
        }
        return true;
    }

    // =====================================================================
    //  아이템
    // =====================================================================
    double fuelMax(ShipType t) {
        return plugin.getConfig().getDouble("ship." + t.key() + ".max-fuel", t.key().equals("cargo") ? 128 : 64);
    }

    double itemFuel(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return 0;
        Double d = it.getItemMeta().getPersistentDataContainer().get(K_FUEL, PersistentDataType.DOUBLE);
        return d == null ? 0 : d;
    }

    ItemStack shipItem(ShipType t) {
        return shipItem(t, 0);
    }

    ItemStack shipItem(ShipType t, double fuel) {
        ItemStack it = new ItemStack(Material.HEART_OF_THE_SEA);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text(t.label(), NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        String desc = t.key().equals("cargo") ? "큰 상자 4개 분량의 화물칸이 있는 6인승 배" : "빠른 6인승 배";
        m.lore(List.of(
                Component.text(desc, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("연료: " + (int) Math.ceil(fuel) + "/" + (int) fuelMax(t) + "L", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("물 위를 보고 우클릭으로 설치", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)));
        m.getPersistentDataContainer().set(K_ITEM, PersistentDataType.STRING, t.key());
        m.getPersistentDataContainer().set(K_FUEL, PersistentDataType.DOUBLE, fuel);
        NamespacedKey model = NamespacedKey.fromString(t.iconModel());
        if (model != null) m.setItemModel(model);
        m.setMaxStackSize(1);
        it.setItemMeta(m);
        return it;
    }

    String shipKey(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(K_ITEM, PersistentDataType.STRING);
    }

    // =====================================================================
    //  화물칸 인벤토리
    // =====================================================================
    static final class HoldHolder implements InventoryHolder {
        final UUID shipId;
        final int idx;
        Inventory inv;

        HoldHolder(UUID shipId, int idx) {
            this.shipId = shipId;
            this.idx = idx;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    Inventory[] makeCargo(UUID id, int n) {
        Inventory[] inv = new Inventory[n];
        for (int i = 0; i < n; i++) {
            HoldHolder h = new HoldHolder(id, i);
            h.inv = Bukkit.createInventory(h, 54, Component.text("화물칸 " + (i + 1) + "/" + n, NamedTextColor.DARK_GRAY));
            inv[i] = h.inv;
        }
        return inv;
    }

    // =====================================================================
    //  배 객체
    // =====================================================================
    final class Ship {
        final UUID id;
        final ShipType type;
        final Interaction hull;
        final ArmorStand[] seats;
        final Interaction[] seatHits;
        final Interaction[] holdHits;
        final ArmorStand[] padCars;
        final Shulker[] pads;
        final double[] padDy;
        final List<BlockDisplay> parts;
        final Inventory[] cargo;
        final World world;
        final double maxSpeed, accel, turn;
        double x, y, z;
        float yaw;
        double speed = 0;
        double fuel = 0;
        double maxFuel = 0;
        boolean dirty = false;
        boolean warned = false;

        Ship(UUID id, ShipType type, Interaction hull, ArmorStand[] seats, Interaction[] seatHits,
             Interaction[] holdHits, ArmorStand[] padCars, Shulker[] pads, List<BlockDisplay> parts,
             Inventory[] cargo, double x, double y, double z, float yaw) {
            this.id = id;
            this.type = type;
            this.maxFuel = fuelMax(type);
            this.hull = hull;
            this.seats = seats;
            this.seatHits = seatHits;
            this.holdHits = holdHits;
            this.padCars = padCars;
            this.pads = pads;
            this.padDy = new double[pads.length];
            this.parts = parts;
            this.cargo = cargo;
            this.world = hull.getWorld();
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            double[] tv = tune.get(type.key());
            this.maxSpeed = tv[0];
            this.accel = tv[1];
            this.turn = tv[2];
        }

        Location origin() {
            return new Location(world, x, y, z, yaw, 0f);
        }

        /** 배 기준 좌표(lx, lz) -> 월드 X/Z 오프셋 */
        double[] off(float lx, float lz) {
            double rad = Math.toRadians(yaw);
            double cs = Math.cos(rad);
            double sn = Math.sin(rad);
            return new double[]{lx * cs - lz * sn, lx * sn + lz * cs};
        }

        Player rider(int i) {
            for (Entity en : seats[i].getPassengers()) if (en instanceof Player pl) return pl;
            return null;
        }

        boolean hasRider() {
            for (int i = 0; i < seats.length; i++) if (rider(i) != null) return true;
            return false;
        }

        boolean isRider(Player p) {
            for (ArmorStand st : seats) if (st.getPassengers().contains(p)) return true;
            return false;
        }

        boolean cargoEmpty() {
            for (Inventory inv : cargo) {
                for (ItemStack it : inv.getContents()) {
                    if (it != null && !it.getType().isAir()) return false;
                }
            }
            return true;
        }

        void saveState() {
            PersistentDataContainer p = hull.getPersistentDataContainer();
            p.set(K_YAW, PersistentDataType.FLOAT, yaw);
            p.set(K_FUEL, PersistentDataType.DOUBLE, fuel);
            saveCargo();
        }

        void saveCargo() {
            PersistentDataContainer p = hull.getPersistentDataContainer();
            for (int i = 0; i < cargo.length && i < K_CARGO.length; i++) {
                p.set(K_CARGO[i], PersistentDataType.BYTE_ARRAY, ItemStack.serializeItemsAsBytes(cargo[i].getContents()));
            }
        }

        void loadCargo() {
            PersistentDataContainer p = hull.getPersistentDataContainer();
            for (int i = 0; i < cargo.length && i < K_CARGO.length; i++) {
                byte[] data = p.get(K_CARGO[i], PersistentDataType.BYTE_ARRAY);
                if (data == null || data.length == 0) continue;
                try {
                    cargo[i].setContents(ItemStack.deserializeItemsFromBytes(data));
                } catch (Exception ex) {
                    plugin.getLogger().warning("화물칸 " + (i + 1) + " 복구 실패: " + ex.getMessage());
                }
            }
        }

        void addFuel(Player p, ItemStack held) {
            int space = (int) Math.floor(maxFuel - fuel + 1e-9);
            if (space <= 0) {
                p.sendActionBar(Component.text("연료가 가득 찼습니다. (" + (int) Math.ceil(fuel) + "/" + (int) maxFuel + "L)", NamedTextColor.YELLOW));
                return;
            }
            int n = Math.min(space, held.getAmount());
            held.setAmount(held.getAmount() - n);
            fuel = Math.min(maxFuel, fuel + n);
            saveState();
            origin().getWorld().playSound(origin(), Sound.ITEM_BUCKET_EMPTY, 0.8f, 1f);
            p.sendActionBar(Component.text("정제유 +" + n + "L  (" + (int) Math.ceil(fuel) + "/" + (int) maxFuel + "L)", NamedTextColor.GREEN));
        }

        void mount(Player p, int want) {
            if (p.isInsideVehicle()) return;
            int idx = want;
            if (idx < 0) {
                for (int i = 0; i < seats.length; i++) {
                    if (rider(i) == null) {
                        idx = i;
                        break;
                    }
                }
            }
            if (idx < 0 || idx >= seats.length) {
                p.sendActionBar(Component.text("빈 자리가 없습니다.", NamedTextColor.RED));
                return;
            }
            if (rider(idx) != null) {
                p.sendActionBar(Component.text("이미 누군가 앉아 있습니다.", NamedTextColor.RED));
                return;
            }
            seats[idx].addPassenger(p);
            if (idx == 0) {
                p.sendActionBar(Component.text("운전석 - W/A/S/D 로 조종", NamedTextColor.AQUA));
            }
        }

        /** 모든 구성 요소를 현재 (x, y, z, yaw) 로 이동 */
        void moveAll() {
            Location base = new Location(world, x, y, z, yaw, 0f);
            hull.teleport(new Location(world, x, y + ANCHOR_DY, z, yaw, 0f));
            for (int i = 0; i < seats.length; i++) {
                double[] o = off(type.seats()[i][0], type.seats()[i][1]);
                seats[i].teleport(new Location(world, x + o[0], y + SEAT_DY, z + o[1], yaw, 0f),
                        TeleportFlag.EntityState.RETAIN_PASSENGERS);
                seatHits[i].teleport(new Location(world, x + o[0], y - 0.4, z + o[1]));
            }
            for (int i = 0; i < holdHits.length; i++) {
                double[] o = off(type.holds()[i][0], type.holds()[i][1]);
                holdHits[i].teleport(new Location(world, x + o[0], y - 0.2, z + o[1]));
            }
            for (int i = 0; i < pads.length; i++) {
                double[] o = off(type.pads()[i][0], type.pads()[i][1]);
                double py = y + DECK_TOP - type.pads()[i][2] - padDy[i];
                padCars[i].teleport(new Location(world, x + o[0], py, z + o[1], yaw, 0f),
                        TeleportFlag.EntityState.RETAIN_PASSENGERS);
            }
            for (BlockDisplay d : parts) d.teleport(base);
        }

        /** 바닥판(슐커)이 탈것 위에서 실제로 놓이는 높이 차이를 재서 갑판 높이를 정확히 맞춤 */
        void calibrate() {
            for (int i = 0; i < pads.length; i++) {
                if (!pads[i].isValid() || !padCars[i].isValid()) continue;
                double d = pads[i].getY() - padCars[i].getY();
                if (Math.abs(d) < 3.0) padDy[i] = d;
            }
            moveAll();
        }

        /** 갑판 위에 서 있는 플레이어를 배와 같이 움직임 (점프 중이거나 공중이면 제외) */
        void carryPlayers(double ox, double oz, float oyaw) {
            double orad = Math.toRadians(oyaw);
            double oc = Math.cos(orad);
            double os = Math.sin(orad);
            double nrad = Math.toRadians(yaw);
            double nc = Math.cos(nrad);
            double ns = Math.sin(nrad);
            double deckY = y + DECK_TOP;
            for (Player pl : world.getNearbyPlayers(new Location(world, ox, y, oz), 8.0)) {
                if (pl.isInsideVehicle() || !pl.isOnGround()) continue;
                Location pp = pl.getLocation();
                double dy = pp.getY() - deckY;
                if (dy < -0.6 || dy > 0.9) continue;
                double wx = pp.getX() - ox;
                double wz = pp.getZ() - oz;
                double lx = wx * oc + wz * os;
                double lz = -wx * os + wz * oc;
                if (Math.abs(lx) > type.halfWidth() + 0.4 || lz < type.sternZ() - 0.4 || lz > type.bowZ()) continue;
                if (pl.getCurrentInput().isJump()) continue;
                double nx = x + lx * nc - lz * ns;
                double nz = z + lx * ns + lz * nc;
                pl.setVelocity(new Vector(nx - pp.getX(), 0.0, nz - pp.getZ()));
            }
        }

        void remove() {
            for (ArmorStand st : seats) {
                for (Entity en : new ArrayList<>(st.getPassengers())) st.removePassenger(en);
                st.remove();
            }
            for (Interaction i : seatHits) i.remove();
            for (Interaction i : holdHits) i.remove();
            for (Shulker sk : pads) sk.remove();
            for (ArmorStand pc : padCars) {
                for (Entity en : new ArrayList<>(pc.getPassengers())) pc.removePassenger(en);
                pc.remove();
            }
            for (BlockDisplay d : parts) d.remove();
            hull.remove();
        }

        void tick() {
            Player drv = rider(0);
            boolean fwd = false, back = false, left = false, right = false;
            if (drv != null) {
                Input in = drv.getCurrentInput();
                fwd = in.isForward();
                back = in.isBackward();
                left = in.isLeft();
                right = in.isRight();
            }

            // 연료: 전진/후진 키를 누르는 동안 1분에 1L 소모
            if (drv != null) {
                boolean engine = fwd != back;
                if (engine && fuel <= 0) {
                    fwd = false;
                    back = false;
                    if (tickCount % 40 == 0)
                        drv.sendActionBar(Component.text("연료가 없습니다! 정제유를 우클릭으로 넣으세요.", NamedTextColor.RED));
                } else if (engine) {
                    fuel = Math.max(0, fuel - 1.0 / fuelTicksPerLiter);
                    dirty = true;
                }
                if (tickCount % 20 == 0 && (fuel > 0 || !engine)) {
                    NamedTextColor col = fuel < maxFuel * 0.15 ? NamedTextColor.RED : NamedTextColor.AQUA;
                    drv.sendActionBar(Component.text("운전석 - W/A/S/D  |  연료 " + (int) Math.ceil(fuel) + "/" + (int) maxFuel + "L", col));
                }
            }

            // 속도
            if (fwd && !back) {
                speed = Math.min(maxSpeed, speed + accel);
            } else if (back && !fwd) {
                speed = speed > 0 ? Math.max(0, speed - accel * 2.5) : Math.max(-maxSpeed * 0.3, speed - accel);
            } else {
                speed *= (drv == null ? 0.90 : drag);
            }
            if (Math.abs(speed) < 0.004) speed = 0;

            // 방향
            float dYaw = 0f;
            if (left && !right) dYaw = (float) -turn;
            else if (right && !left) dYaw = (float) turn;
            if (dYaw != 0f) {
                double f = 0.45 + 0.55 * Math.min(1.0, Math.abs(speed) / maxSpeed);
                dYaw *= (float) f;
                if (speed < -0.01) dYaw = -dYaw;
            }

            if (speed == 0 && dYaw == 0f) {
                if (dirty) {
                    saveState();
                    dirty = false;
                }
                return;
            }

            float ny = yaw + dYaw;
            if (ny >= 180f) ny -= 360f;
            if (ny < -180f) ny += 360f;
            if (!canOccupy(world, type, x, y, z, ny)) ny = yaw; // 제자리 회전이 막히면 회전 취소

            double rad = Math.toRadians(ny);
            double dx = -Math.sin(rad);
            double dz = Math.cos(rad);
            double px = x;
            double pz = z;
            boolean blocked = false;
            if (speed != 0) {
                int steps = (int) Math.max(1, Math.ceil(Math.abs(speed) / 0.5));
                double sx = dx * speed / steps;
                double sz = dz * speed / steps;
                for (int i = 0; i < steps; i++) {
                    if (!canOccupy(world, type, px + sx, y, pz + sz, ny)) {
                        blocked = true;
                        break;
                    }
                    px += sx;
                    pz += sz;
                }
            }
            if (blocked) speed *= 0.3; // 육지/장애물에 부딪히면 감속

            if (px == x && pz == z && ny == yaw) return;
            double ox = x;
            double oz = z;
            float oyaw = yaw;
            x = px;
            z = pz;
            yaw = ny;
            dirty = true;
            moveAll();
            carryPlayers(ox, oz, oyaw);

            // 물보라
            if (Math.abs(speed) > 0.15 && tickCount % 2 == 0) {
                double[] o = off(0f, type.sternZ() - 0.3f);
                world.spawnParticle(Particle.SPLASH, x + o[0], y - LIFT, z + o[1], 5, 0.4, 0.05, 0.4, 0.1);
            }
        }
    }

    // =====================================================================
    //  생성 / 복구
    // =====================================================================
    Ship spawnShip(ShipType t, Location at, float yaw) {
        return spawnShip(t, at, yaw, 0);
    }

    Ship spawnShip(ShipType t, Location at, float yaw, double fuel) {
        UUID id = UUID.randomUUID();
        String sid = id.toString();
        World w = at.getWorld();
        double x = at.getX();
        double y = at.getY();
        double z = at.getZ();
        double rad = Math.toRadians(yaw);
        double cs = Math.cos(rad);
        double sn = Math.sin(rad);
        Location base = new Location(w, x, y, z, yaw, 0f);

        Interaction hull = w.spawn(new Location(w, x, y + ANCHOR_DY, z, yaw, 0f), Interaction.class, ent -> {
            ent.setInteractionWidth(t.hullWidth());
            ent.setInteractionHeight(t.hullHeight());
            ent.setResponsive(true);
            tag(ent, sid, "hull", 0);
            ent.getPersistentDataContainer().set(K_TYPE, PersistentDataType.STRING, t.key());
            ent.getPersistentDataContainer().set(K_YAW, PersistentDataType.FLOAT, yaw);
        });

        int ns = t.seats().length;
        ArmorStand[] seats = new ArmorStand[ns];
        Interaction[] seatHits = new Interaction[ns];
        for (int i = 0; i < ns; i++) {
            final int idx = i;
            double wx = x + t.seats()[i][0] * cs - t.seats()[i][1] * sn;
            double wz = z + t.seats()[i][0] * sn + t.seats()[i][1] * cs;
            seats[i] = w.spawn(new Location(w, wx, y + SEAT_DY, wz, yaw, 0f), ArmorStand.class, a -> {
                a.setVisible(false);
                a.setGravity(false);
                a.setMarker(true);
                a.setSmall(true);
                a.setInvulnerable(true);
                a.setSilent(true);
                tag(a, sid, "seat", idx);
            });
            seatHits[i] = w.spawn(new Location(w, wx, y - 0.4, wz), Interaction.class, h -> {
                h.setInteractionWidth(0.8f);
                h.setInteractionHeight(1.3f);
                h.setResponsive(true);
                tag(h, sid, "seathit", idx);
            });
        }

        int nh = t.holds().length;
        Interaction[] holdHits = new Interaction[nh];
        for (int i = 0; i < nh; i++) {
            final int idx = i;
            double wx = x + t.holds()[i][0] * cs - t.holds()[i][1] * sn;
            double wz = z + t.holds()[i][0] * sn + t.holds()[i][1] * cs;
            holdHits[i] = w.spawn(new Location(w, wx, y - 0.2, wz), Interaction.class, h -> {
                h.setInteractionWidth(0.95f);
                h.setInteractionHeight(1.1f);
                h.setResponsive(true);
                tag(h, sid, "hold", idx);
            });
        }

        int np = t.pads().length;
        ArmorStand[] padCars = new ArmorStand[np];
        Shulker[] pads = new Shulker[np];
        for (int i = 0; i < np; i++) {
            final int idx = i;
            final float sc = t.pads()[i][2];
            double wx = x + t.pads()[i][0] * cs - t.pads()[i][1] * sn;
            double wz = z + t.pads()[i][0] * sn + t.pads()[i][1] * cs;
            double py = y + DECK_TOP - sc;
            padCars[i] = w.spawn(new Location(w, wx, py, wz, yaw, 0f), ArmorStand.class, a -> {
                a.setVisible(false);
                a.setGravity(false);
                a.setMarker(true);
                a.setSmall(true);
                a.setInvulnerable(true);
                a.setSilent(true);
                tag(a, sid, "padcar", idx);
            });
            pads[i] = w.spawn(new Location(w, wx, py, wz, yaw, 0f), Shulker.class, sk -> {
                sk.setAI(false);
                sk.setInvisible(true);
                sk.setSilent(true);
                sk.setInvulnerable(true);
                sk.setGravity(false);
                sk.setPeek(0f);
                sk.setRemoveWhenFarAway(false);
                Attribute attr = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("scale"));
                AttributeInstance ai = attr == null ? null : sk.getAttribute(attr);
                if (ai != null) ai.setBaseValue(sc);
                tag(sk, sid, "pad", idx);
            });
            padCars[i].addPassenger(pads[i]); // 탈것에 태워 두면 슐커가 벽을 찾아 순간이동하지 않음
        }

        List<BlockDisplay> parts = new ArrayList<>();
        for (int i = 0; i < t.parts().size(); i++) {
            final Part pt = t.parts().get(i);
            final int idx = i;
            parts.add(w.spawn(base, BlockDisplay.class, d -> {
                d.setBlock(Bukkit.createBlockData(pt.data()));
                d.setTransformation(transformOf(pt));
                d.setTeleportDuration(3);
                d.setViewRange(1.5f);
                tag(d, sid, "part", idx);
            }));
        }

        Ship ship = new Ship(id, t, hull, seats, seatHits, holdHits, padCars, pads, parts,
                makeCargo(id, nh), x, y, z, yaw);
        ship.fuel = Math.min(fuel, ship.maxFuel);
        ships.put(id, ship);
        ship.saveState();
        Bukkit.getScheduler().runTaskLater(plugin, ship::calibrate, 3L);
        return ship;
    }

    void tryRegister(Interaction hb, int attempt) {
        if (!hb.isValid()) return;
        PersistentDataContainer hp = hb.getPersistentDataContainer();
        if (!"hull".equals(hp.get(K_ROLE, PersistentDataType.STRING))) return;
        String sid = hp.get(K_ID, PersistentDataType.STRING);
        String tk = hp.get(K_TYPE, PersistentDataType.STRING);
        if (sid == null || tk == null) return;
        ShipType t = types.get(tk);
        if (t == null) return;
        UUID id;
        try {
            id = UUID.fromString(sid);
        } catch (IllegalArgumentException ex) {
            return;
        }
        if (ships.containsKey(id)) return;

        ArmorStand[] seats = new ArmorStand[t.seats().length];
        Interaction[] seatHits = new Interaction[t.seats().length];
        Interaction[] holdHits = new Interaction[t.holds().length];
        ArmorStand[] padCars = new ArmorStand[t.pads().length];
        Shulker[] pads = new Shulker[t.pads().length];
        List<BlockDisplay> parts = new ArrayList<>();
        for (Entity en : hb.getNearbyEntities(8, 6, 8)) {
            PersistentDataContainer p = en.getPersistentDataContainer();
            if (!sid.equals(p.get(K_ID, PersistentDataType.STRING))) continue;
            String role = p.get(K_ROLE, PersistentDataType.STRING);
            if (role == null) continue;
            int idx = idxOf(en);
            switch (role) {
                case "seat" -> {
                    if (en instanceof ArmorStand a && idx >= 0 && idx < seats.length) seats[idx] = a;
                }
                case "seathit" -> {
                    if (en instanceof Interaction h && idx >= 0 && idx < seatHits.length) seatHits[idx] = h;
                }
                case "hold" -> {
                    if (en instanceof Interaction h && idx >= 0 && idx < holdHits.length) holdHits[idx] = h;
                }
                case "padcar" -> {
                    if (en instanceof ArmorStand a && idx >= 0 && idx < padCars.length) padCars[idx] = a;
                }
                case "pad" -> {
                    if (en instanceof Shulker sk && idx >= 0 && idx < pads.length) pads[idx] = sk;
                }
                case "part" -> {
                    if (en instanceof BlockDisplay d) parts.add(d);
                }
                default -> {
                }
            }
        }
        boolean complete = parts.size() == t.parts().size();
        for (ArmorStand a : seats) if (a == null) complete = false;
        for (Interaction h : seatHits) if (h == null) complete = false;
        for (Interaction h : holdHits) if (h == null) complete = false;
        for (ArmorStand a : padCars) if (a == null) complete = false;
        for (Shulker sk : pads) if (sk == null) complete = false;
        if (!complete) {
            if (attempt < 5) {
                final int next = attempt + 1;
                Bukkit.getScheduler().runTaskLater(plugin, () -> tryRegister(hb, next), 20L);
            }
            return;
        }

        Float sy = hp.get(K_YAW, PersistentDataType.FLOAT);
        float yaw = sy == null ? hb.getLocation().getYaw() : sy;
        Ship ship = new Ship(id, t, hb, seats, seatHits, holdHits, padCars, pads, parts,
                makeCargo(id, holdHits.length), hb.getX(), hb.getY() - ANCHOR_DY, hb.getZ(), yaw);
        ship.loadCargo();
        Double fv = hp.get(K_FUEL, PersistentDataType.DOUBLE);
        ship.fuel = fv == null ? 0 : Math.min(fv, ship.maxFuel);
        ships.put(id, ship);
        ship.moveAll();
        Bukkit.getScheduler().runTaskLater(plugin, ship::calibrate, 3L);
    }

    // =====================================================================
    //  틱
    // =====================================================================
    void tick() {
        tickCount++;
        Iterator<Ship> it = ships.values().iterator();
        while (it.hasNext()) {
            Ship s = it.next();
            if (!s.hull.isValid()) {
                it.remove();
                continue;
            }
            try {
                s.tick();
            } catch (Exception ex) {
                if (!s.warned) {
                    s.warned = true;
                    plugin.getLogger().warning("함선 처리 중 오류: " + ex);
                }
            }
        }
    }

    // =====================================================================
    //  이벤트
    // =====================================================================
    /** 함선 아이템을 들고 물을 향해 우클릭 -> 설치 */
    @EventHandler
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = e.getItem();
        String key = shipKey(item);
        if (key == null) return;
        e.setCancelled(true);
        ShipType t = types.get(key);
        if (t == null) return;
        Player p = e.getPlayer();

        RayTraceResult r = p.rayTraceBlocks(8.0, FluidCollisionMode.ALWAYS);
        Block b = r == null ? null : r.getHitBlock();
        if (b == null || !isWater(b)) {
            p.sendActionBar(Component.text("물 위를 바라보고 사용하세요.", NamedTextColor.RED));
            return;
        }
        int up = 0;
        while (up < 4 && isWater(b.getRelative(BlockFace.UP))) {
            b = b.getRelative(BlockFace.UP);
            up++;
        }
        double sy = b.getY() + surfaceHeight(b);
        Location at = new Location(b.getWorld(), r.getHitPosition().getX(), sy + LIFT, r.getHitPosition().getZ());
        float yaw = p.getLocation().getYaw();
        if (!canOccupy(at.getWorld(), t, at.getX(), at.getY(), at.getZ(), yaw)) {
            p.sendActionBar(Component.text("여기엔 놓을 수 없습니다. (물이 넓고 위가 비어 있어야 합니다)", NamedTextColor.RED));
            return;
        }
        spawnShip(t, at, yaw, itemFuel(item));
        at.getWorld().playSound(at, Sound.ENTITY_GENERIC_SPLASH, 1f, 0.8f);
        if (p.getGameMode() != GameMode.CREATIVE) item.setAmount(item.getAmount() - 1);
    }

    @EventHandler
    public void onClick(PlayerInteractEntityEvent e) {
        Entity en = e.getRightClicked();
        if (!isShipEntity(en)) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        Ship s = shipOf(en);
        if (s == null) return;
        Player p = e.getPlayer();
        String role = role(en);
        int idx = idxOf(en);
        if (role == null) return;
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (!role.equals("hold") && plugin.is(hand, plugin.oilItem)) {
            s.addFuel(p, hand);
            return;
        }
        switch (role) {
            case "hold" -> {
                if (idx >= 0 && idx < s.cargo.length) p.openInventory(s.cargo[idx]);
            }
            case "seathit" -> s.mount(p, idx);
            case "hull" -> s.mount(p, -1);
            default -> {
            }
        }
    }

    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        Entity en = e.getEntity();
        if (!isShipEntity(en)) return;
        e.setCancelled(true);
        if (e instanceof EntityDamageByEntityEvent d && d.getDamager() instanceof Player p) {
            tryPickup(p, en);
        }
    }

    @EventHandler
    public void onPreAttack(PrePlayerAttackEntityEvent e) {
        if (!isShipEntity(e.getAttacked())) return;
        e.setCancelled(true);
        tryPickup(e.getPlayer(), e.getAttacked());
    }

    void tryPickup(Player p, Entity en) {
        Ship s = shipOf(en);
        if (s == null) return;
        if (s.hasRider()) {
            if (!s.isRider(p)) p.sendActionBar(Component.text("누군가 타고 있어서 회수할 수 없습니다.", NamedTextColor.RED));
            return;
        }
        if (!p.isSneaking()) {
            p.sendActionBar(Component.text("웅크린 채로 때리면 함선을 회수합니다.", NamedTextColor.YELLOW));
            return;
        }
        if (!s.cargoEmpty()) {
            p.sendActionBar(Component.text("화물칸을 모두 비워야 회수할 수 있습니다.", NamedTextColor.RED));
            return;
        }
        Location l = s.origin().add(0, 0.5, 0);
        l.getWorld().dropItemNaturally(l, shipItem(s.type, s.fuel));
        s.remove();
        ships.remove(s.id);
    }

    // ---- 화물칸 저장 ----
    void saveLater(Inventory inv) {
        if (inv.getHolder() instanceof HoldHolder h) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                Ship s = ships.get(h.shipId);
                if (s != null) s.saveCargo();
            });
        }
    }

    @EventHandler
    public void onInvClick(InventoryClickEvent e) {
        saveLater(e.getInventory());
    }

    @EventHandler
    public void onInvDrag(InventoryDragEvent e) {
        saveLater(e.getInventory());
    }

    @EventHandler
    public void onInvClose(InventoryCloseEvent e) {
        if (e.getInventory().getHolder() instanceof HoldHolder h) {
            Ship s = ships.get(h.shipId);
            if (s != null) s.saveCargo();
        }
    }

    // ---- 청크 로드/언로드 ----
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        for (Entity en : e.getEntities()) {
            if (en instanceof Interaction hb && "hull".equals(role(en))) {
                Bukkit.getScheduler().runTask(plugin, () -> tryRegister(hb, 0));
            }
        }
    }

    @EventHandler
    public void onEntitiesUnload(EntitiesUnloadEvent e) {
        for (Entity en : e.getEntities()) {
            if (en instanceof Interaction && "hull".equals(role(en))) {
                Ship s = shipOf(en);
                if (s != null) {
                    s.saveState();
                    ships.remove(s.id);
                }
            }
        }
    }

    // =====================================================================
    //  명령어  /ship give <fast|cargo>  /  /ship remove
    // =====================================================================
    boolean command(CommandSender sender, String[] a) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("인게임에서만 사용할 수 있습니다.");
            return true;
        }
        if (a.length >= 2 && a[0].equalsIgnoreCase("give")) {
            ShipType t = types.get(a[1].toLowerCase());
            if (t == null) {
                p.sendMessage("종류: " + String.join(", ", types.keySet()));
                return true;
            }
            p.getInventory().addItem(shipItem(t));
            p.sendMessage("지급: " + t.label());
            return true;
        }
        if (a.length >= 1 && a[0].equalsIgnoreCase("remove")) {
            Ship best = null;
            double bd = 8 * 8;
            for (Ship s : ships.values()) {
                if (s.world != p.getWorld()) continue;
                double d = s.origin().distanceSquared(p.getLocation());
                if (d < bd) {
                    bd = d;
                    best = s;
                }
            }
            if (best == null) {
                p.sendMessage("8칸 이내에 함선이 없습니다.");
                return true;
            }
            Location l = best.origin().add(0, 1, 0);
            for (Inventory inv : best.cargo) {
                for (ItemStack it : inv.getContents()) {
                    if (it != null && !it.getType().isAir()) l.getWorld().dropItemNaturally(l, it);
                }
                inv.clear();
            }
            best.remove();
            ships.remove(best.id);
            p.sendMessage("가장 가까운 함선을 제거했습니다. (화물은 바닥에 떨어집니다)");
            return true;
        }
        p.sendMessage("/ship give <fast|cargo>  -  함선 아이템 받기");
        p.sendMessage("/ship remove  -  가장 가까운 함선 제거(8칸 이내)");
        return true;
    }
}
