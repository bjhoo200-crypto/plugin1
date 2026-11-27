package kr.example.cannon;

import io.papermc.paper.entity.TeleportFlag;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandSender;
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
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
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
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static kr.example.cannon.ShipManager.Part;
import static kr.example.cannon.ShipManager.box;

/**
 * 기차: 조종칸(기관차) / 화물칸 / 탑승칸(객차) + 철로.
 * - 철로 한 조각은 가로 5 x 세로 1. 쭉 이어서 깔고, 지형을 따라 1칸씩 오르내리는 경사도 가능
 * - 옆으로 꺾으려면 시작/끝 철로를 각각 우클릭(빈손)해서 선택 -> 사이를 곡선으로 자동 연결(조각 수는 거리만큼)
 * - 갈림길: 이미 이어진 끝에 웅크리고 설치. 갈림길 앞 철로를 웅크리고 우클릭하면 방향 전환, 지나기 직전 A/D 로도 선택
 * - 기차는 철로를 따라 자동 주행. 운전자가 W 로 출발시키면 연료가 있는 동안 계속 달림(S = 정지)
 * 칸 좌표계: 칸 중심(레일 윗면) 기준, +Z = 앞, +X = 왼쪽, +Y = 위
 */
public final class TrainManager implements Listener {

    static final double SPACING = 6.4;    // 칸 중심 사이 거리
    static final float FLOOR = 0.5f;      // 바닥 윗면 높이 (레일 윗면 기준)
    static final double SEAT_UP = 0.16;
    static final int CRATES = 16;
    static final double TILE_TOP = 0.2;   // 레일 윗면 높이 (지면 기준)

    // =====================================================================
    //  칸 종류 정의
    // =====================================================================
    /** pads 원소: {x, z, 크기, 종류(0=바닥, 1=벽)} */
    record CarType(String key, String label, Material icon, float[][] seats, float[][] pads,
                   boolean loco, boolean cargo, List<Part> parts) {}

    static void chair(List<Part> o, float x, float z, String mat) {
        o.add(box(mat, x, FLOOR + 0.07f, z, 0.8f, 0.14f, 0.8f));
        o.add(box(mat, x, FLOOR + 0.35f, z - 0.4f, 0.8f, 0.5f, 0.1f));
    }

    static void chassis(List<Part> o, String floorMat) {
        o.add(box("minecraft:black_concrete", 0f, 0.2f, 0f, 4.4f, 0.2f, 5.6f));
        o.add(box(floorMat, 0f, 0.4f, 0f, 5.0f, 0.2f, 6.0f));
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                o.add(box("minecraft:black_concrete", sx * 1.7f, 0.1f, sz * 2.0f, 0.5f, 0.2f, 1.4f));
            }
        }
        for (int sz = -1; sz <= 1; sz += 2) {
            o.add(box("minecraft:iron_block", 0f, 0.35f, sz * 3.2f, 0.3f, 0.3f, 0.5f));
        }
    }

    static void floorPads(List<float[]> o, boolean cabOnly) {
        if (cabOnly) {
            o.add(new float[]{-1.25f, -1.9f, 2.5f, 0});
            o.add(new float[]{1.25f, -1.9f, 2.5f, 0});
            o.add(new float[]{-1.25f, -0.6f, 2.5f, 0});
            o.add(new float[]{1.25f, -0.6f, 2.5f, 0});
        } else {
            for (float z : new float[]{-2f, 0f, 2f}) {
                o.add(new float[]{-1.25f, z, 2.5f, 0});
                o.add(new float[]{1.25f, z, 2.5f, 0});
            }
        }
    }

    /** 옆벽: 안쪽 면이 x=±2.3 에 오도록 바깥쪽으로 붙인 투명 충돌 상자 */
    static void sideWalls(List<float[]> o, float[] zs, float size) {
        for (int sd = -1; sd <= 1; sd += 2) {
            for (float z : zs) o.add(new float[]{sd * (2.3f + size / 2f), z, size, 1});
        }
    }

    /** 끝벽 모서리(가운데 문은 비워 둠) */
    static void endCorners(List<float[]> o, int sz) {
        for (int sd = -1; sd <= 1; sd += 2) o.add(new float[]{sd * 1.75f, sz * 3.65f, 1.5f, 1});
    }

    static CarType passType() {
        List<Part> p = new ArrayList<>();
        chassis(p, "minecraft:gray_concrete");
        p.add(box("minecraft:red_concrete", 0f, FLOOR + 0.01f, 0f, 1.0f, 0.02f, 5.6f));
        // 옆벽: 위에서 3번째 칸(z 0~1, 두 번째 의자 앞)은 문으로 뚫려 있음
        for (int sd = -1; sd <= 1; sd += 2) {
            float x = sd * 2.4f;
            float[][] seg = {{-1.5f, 3.0f}, {2.0f, 2.0f}};   // {중심 z, 길이}
            for (float[] sg : seg) {
                p.add(box("minecraft:blue_concrete", x, FLOOR + 0.45f, sg[0], 0.2f, 0.9f, sg[1]));
                p.add(box("minecraft:light_blue_stained_glass", x, FLOOR + 1.35f, sg[0], 0.1f, 0.9f, sg[1]));
            }
            p.add(box("minecraft:white_concrete", x, FLOOR + 1.9f, 0f, 0.24f, 0.2f, 6.0f));
            for (float z : new float[]{-2.9f, -1f, -0.1f, 1.1f, 2.9f}) {
                p.add(box("minecraft:blue_concrete", x, FLOOR + 1.35f, z, 0.22f, 0.9f, 0.2f));
            }
            p.add(box("minecraft:blue_concrete", x, FLOOR + 0.5f, -0.1f, 0.22f, 1.0f, 0.2f));
            p.add(box("minecraft:blue_concrete", x, FLOOR + 0.5f, 1.1f, 0.22f, 1.0f, 0.2f));
        }
        for (int sz = -1; sz <= 1; sz += 2) {
            for (int sd = -1; sd <= 1; sd += 2) {
                p.add(box("minecraft:blue_concrete", sd * 1.75f, FLOOR + 1.0f, sz * 2.9f, 1.5f, 2.0f, 0.2f));
            }
            p.add(box("minecraft:blue_concrete", 0f, FLOOR + 1.9f, sz * 2.9f, 2.0f, 0.2f, 0.2f));
        }
        p.add(box("minecraft:white_concrete", 0f, FLOOR + 2.075f, 0f, 5.0f, 0.15f, 6.0f));
        for (float z : new float[]{-1.5f, 1.5f}) {
            p.add(box("minecraft:sea_lantern", 0f, FLOOR + 1.95f, z, 0.4f, 0.1f, 0.4f));
        }
        float[][] seats = {{-1f, 2f}, {1f, 2f}, {-1f, 0f}, {1f, 0f}, {-1f, -2f}, {1f, -2f}};
        for (float[] s : seats) chair(p, s[0], s[1], "minecraft:gray_concrete");
        List<float[]> pd = new ArrayList<>();
        floorPads(pd, false);
        for (int sd = -1; sd <= 1; sd += 2) {   // 충돌 벽 (문 자리 z 0~1 은 비움)
            pd.add(new float[]{sd * 3.3f, -2.0f, 2.0f, 1});
            pd.add(new float[]{sd * 3.05f, -0.75f, 1.5f, 1});
            pd.add(new float[]{sd * 3.3f, 2.0f, 2.0f, 1});
        }
        endCorners(pd, -1);
        endCorners(pd, 1);
        return new CarType("pass", "탑승칸", Material.MINECART, seats, pd.toArray(new float[0][]),
                false, false, List.copyOf(p));
    }

    static CarType cargoType() {
        List<Part> p = new ArrayList<>();
        chassis(p, "minecraft:brown_concrete");
        for (int sd = -1; sd <= 1; sd += 2) {
            p.add(box("minecraft:brown_concrete", sd * 2.4f, FLOOR + 0.6f, 0f, 0.2f, 1.2f, 6.0f));
            p.add(box("minecraft:orange_concrete", sd * 2.4f, FLOOR + 1.25f, 0f, 0.26f, 0.1f, 6.0f));
        }
        for (int sz = -1; sz <= 1; sz += 2) {
            p.add(box("minecraft:brown_concrete", 0f, FLOOR + 0.6f, sz * 2.9f, 5.0f, 1.2f, 0.2f));
            p.add(box("minecraft:orange_concrete", 0f, FLOOR + 1.25f, sz * 2.9f, 5.0f, 0.1f, 0.26f));
        }
        List<float[]> pd = new ArrayList<>();
        floorPads(pd, false);
        sideWalls(pd, new float[]{-2.25f, -0.75f, 0.75f, 2.25f}, 1.5f);
        for (int sz = -1; sz <= 1; sz += 2) {
            for (float x : new float[]{-1.5f, 0f, 1.5f}) pd.add(new float[]{x, sz * 3.65f, 1.5f, 1});
        }
        return new CarType("cargo", "화물칸", Material.CHEST_MINECART, new float[0][], pd.toArray(new float[0][]),
                false, true, List.copyOf(p));
    }

    static CarType locoType() {
        List<Part> p = new ArrayList<>();
        chassis(p, "minecraft:gray_concrete");
        p.add(box("minecraft:red_concrete", 0f, FLOOR + 0.9f, 1.9f, 3.6f, 1.8f, 2.4f));
        p.add(box("minecraft:black_concrete", 0f, FLOOR + 1.85f, 1.9f, 3.2f, 0.1f, 2.2f));
        p.add(box("minecraft:black_concrete", 0f, FLOOR + 2.3f, 2.4f, 0.6f, 0.8f, 0.6f));
        p.add(box("minecraft:iron_block", 0f, 0.6f, 3.3f, 3.0f, 0.5f, 0.4f));
        p.add(box("minecraft:sea_lantern", 0f, FLOOR + 1.4f, 3.15f, 0.5f, 0.4f, 0.1f));
        p.add(box("minecraft:furnace[facing=north,lit=false]", 0f, FLOOR + 0.5f, 0.6f, 0.9f, 0.9f, 0.5f));
        for (int sd = -1; sd <= 1; sd += 2) {
            float x = sd * 2.4f;
            p.add(box("minecraft:red_concrete", x, FLOOR + 0.45f, -1.15f, 0.2f, 0.9f, 3.7f));
            p.add(box("minecraft:light_blue_stained_glass", x, FLOOR + 1.35f, -1.15f, 0.1f, 0.9f, 3.7f));
            p.add(box("minecraft:black_concrete", x, FLOOR + 1.9f, -1.15f, 0.24f, 0.2f, 3.7f));
        }
        for (int sd = -1; sd <= 1; sd += 2) {
            p.add(box("minecraft:red_concrete", sd * 1.75f, FLOOR + 1.0f, -2.9f, 1.5f, 2.0f, 0.2f));
        }
        p.add(box("minecraft:red_concrete", 0f, FLOOR + 1.9f, -2.9f, 2.0f, 0.2f, 0.2f));
        p.add(box("minecraft:black_concrete", 0f, FLOOR + 2.075f, -1.1f, 5.2f, 0.15f, 3.9f));
        p.add(box("minecraft:black_concrete", 0f, FLOOR + 0.45f, -0.25f, 1.4f, 0.9f, 0.5f));
        p.add(box("minecraft:redstone_lamp[lit=true]", 0f, FLOOR + 1.0f, -0.25f, 0.4f, 0.2f, 0.4f));
        chair(p, 0f, -1.2f, "minecraft:black_concrete");
        float[][] seats = {{0f, -1.2f}};
        List<float[]> pd = new ArrayList<>();
        floorPads(pd, true);
        sideWalls(pd, new float[]{-2.0f, -0.3f}, 2.0f);
        endCorners(pd, -1);
        for (float z : new float[]{1.6f, 2.2f}) {
            for (float x : new float[]{-0.9f, 0.9f}) pd.add(new float[]{x, z, 1.8f, 1});
        }
        return new CarType("loco", "조종칸", Material.FURNACE_MINECART, seats, pd.toArray(new float[0][]),
                true, false, List.copyOf(p));
    }



    /** 칸 회전(요, 피치[도, 아래로 +])을 적용한 위치 오프셋 */
    static double[] rot3(double yaw, double pitch, double lx, double ly, double lz) {
        double p = Math.toRadians(pitch);
        double ly2 = ly * Math.cos(p) - lz * Math.sin(p);
        double lz2 = ly * Math.sin(p) + lz * Math.cos(p);
        double[] o = rot(yaw, lx, lz2);
        return new double[]{o[0], ly2, o[1]};
    }

    static double[] rot(double yaw, double lx, double lz) {
        double rad = Math.toRadians(yaw);
        double cs = Math.cos(rad);
        double sn = Math.sin(rad);
        return new double[]{lx * cs - lz * sn, lx * sn + lz * cs};
    }

    static double norm180(double a) {
        return ((a % 360) + 540) % 360 - 180;
    }

    // =====================================================================
    //  철로 조각 (가로 5 x 세로 1)
    // =====================================================================
    final class Piece {
        final UUID id;
        final World world;
        final double[] a;   // 시작 끝 중점 (지면 윗면 높이)
        final double[] b;   // 반대 끝 중점
        String bed;
        int state = 0;      // 갈림길 선택
        final List<UUID> la = new ArrayList<>();
        final List<UUID> lb = new ArrayList<>();
        Interaction hit;
        final List<BlockDisplay> shown = new ArrayList<>();

        Piece(UUID id, World world, double[] a, double[] b, String bed) {
            this.id = id;
            this.world = world;
            this.a = a;
            this.b = b;
            this.bed = bed;
        }

        double[] end(int e) {
            return e == 0 ? a : b;
        }

        List<UUID> links(int e) {
            return e == 0 ? la : lb;
        }

        double horiz() {
            return Math.hypot(b[0] - a[0], b[2] - a[2]);
        }

        double len() {
            return Math.max(0.05, Math.sqrt(sq(b[0] - a[0]) + sq(b[1] - a[1]) + sq(b[2] - a[2])));
        }

        double[] at(double t) {
            return new double[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t};
        }

        /** a -> b 로 갈 때 진행 방향 요(도) */
        double yawFwd() {
            return Math.toDegrees(Math.atan2(-(b[0] - a[0]), b[2] - a[2]));
        }

        /** a -> b 로 갈 때 피치(도, 오르막이 음수) */
        double pitchFwd() {
            return -Math.toDegrees(Math.atan2(b[1] - a[1], Math.max(0.01, horiz())));
        }

        int maxFork() {
            return Math.max(la.size(), lb.size());
        }

        void save() {
            PersistentDataContainer p = hit.getPersistentDataContainer();
            p.set(K_PIECE, PersistentDataType.STRING, id + ";" + a[0] + "," + a[1] + "," + a[2] + ";"
                    + b[0] + "," + b[1] + "," + b[2] + ";" + state);
            p.set(K_PLINK, PersistentDataType.STRING, join(la) + "|" + join(lb));
            p.set(K_TBED, PersistentDataType.STRING, bed);
        }

        void clearVisual() {
            for (BlockDisplay d : shown) d.remove();
            shown.clear();
            for (Entity en : hit.getNearbyEntities(3, 3, 3)) {
                if (en instanceof BlockDisplay && id.toString().equals(en.getPersistentDataContainer().get(K_TID, PersistentDataType.STRING))) {
                    en.remove();
                }
            }
        }

        void addPart(Location loc, Part pt) {
            BlockDisplay d = world.spawn(loc, BlockDisplay.class, e -> {
                e.setBlock(Bukkit.createBlockData(pt.data()));
                e.setTransformation(ShipManager.transformOf(pt));
                e.setViewRange(2.0f);
                e.getPersistentDataContainer().set(K_TID, PersistentDataType.STRING, id.toString());
            });
            shown.add(d);
        }

        void rebuild() {
            clearVisual();
            double[] m = at(0.5);
            Location loc = new Location(world, m[0], m[1], m[2], (float) yawFwd(), (float) pitchFwd());
            float l = (float) len() + 0.06f;
            addPart(loc, box(bed, 0f, 0.05f, 0f, 4.96f, 0.1f, l));
            addPart(loc, box("minecraft:spruce_planks", 0f, 0.13f, 0f, 3.9f, 0.06f, 0.3f));
            addPart(loc, box("minecraft:iron_block", 1.7f, 0.16f, 0f, 0.16f, 0.08f, l));
            addPart(loc, box("minecraft:iron_block", -1.7f, 0.16f, 0f, 0.16f, 0.08f, l));
            if (maxFork() >= 2) {
                String col = state % 3 == 0 ? "minecraft:lime_concrete" : state % 3 == 1 ? "minecraft:red_concrete" : "minecraft:yellow_concrete";
                addPart(loc, box(col, 0f, 0.25f, 0f, 0.5f, 0.1f, 0.5f));
            }
            save();
        }
    }

    static double sq(double v) {
        return v * v;
    }

    static String join(List<UUID> l) {
        StringBuilder sb = new StringBuilder();
        for (UUID u : l) {
            if (sb.length() > 0) sb.append(',');
            sb.append(u);
        }
        return sb.toString();
    }

    static void parseLinks(String s, List<UUID> out) {
        if (s == null || s.isEmpty()) return;
        for (String t : s.split(",")) {
            try {
                out.add(UUID.fromString(t));
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    // =====================================================================
    //  필드
    // =====================================================================
    final CannonPlugin plugin;
    final Map<String, CarType> types = new LinkedHashMap<>();
    final Map<UUID, Car> cars = new HashMap<>();
    final Map<UUID, Piece> pieces = new HashMap<>();
    final Map<UUID, UUID> selected = new HashMap<>();   // 플레이어 -> 곡선 시작 철로
    final Map<UUID, Long> lastPlace = new HashMap<>();
    long tickCount = 0;

    double maxSpeed = 0.45, curveSpeed = 0.3, accel = 0.008, brake = 0.03;
    double coalSeconds = 80, oilSeconds = 120;
    String defaultBed = "minecraft:gravel";

    NamespacedKey K_ID, K_ROLE, K_IDX, K_TYPE, K_YAW, K_NEXT, K_ITEM, K_BURN, K_FUEL, K_C0, K_C1, K_RUN;
    NamespacedKey K_PIECE, K_PLINK, K_TBED, K_TID;

    TrainManager(CannonPlugin plugin) {
        this.plugin = plugin;
    }

    void enable() {
        K_ID = new NamespacedKey(plugin, "train_id");
        K_ROLE = new NamespacedKey(plugin, "train_role");
        K_IDX = new NamespacedKey(plugin, "train_idx");
        K_TYPE = new NamespacedKey(plugin, "train_type");
        K_YAW = new NamespacedKey(plugin, "train_yaw");
        K_NEXT = new NamespacedKey(plugin, "train_next");
        K_ITEM = new NamespacedKey(plugin, "train_item");
        K_BURN = new NamespacedKey(plugin, "train_burn");
        K_FUEL = new NamespacedKey(plugin, "train_fuelinv");
        K_C0 = new NamespacedKey(plugin, "train_cargo_0");
        K_C1 = new NamespacedKey(plugin, "train_cargo_1");
        K_RUN = new NamespacedKey(plugin, "train_run");
        K_PIECE = new NamespacedKey(plugin, "track_piece");
        K_PLINK = new NamespacedKey(plugin, "track_links");
        K_TBED = new NamespacedKey(plugin, "track_bed");
        K_TID = new NamespacedKey(plugin, "track_tid");

        types.put("loco", locoType());
        types.put("cargo", cargoType());
        types.put("pass", passType());

        var c = plugin.getConfig();
        maxSpeed = c.getDouble("train.max-speed", 0.45);
        curveSpeed = c.getDouble("train.curve-speed", 0.3);
        accel = c.getDouble("train.accel", 0.008);
        brake = c.getDouble("train.brake", 0.03);
        coalSeconds = c.getDouble("train.coal-seconds", 80);
        oilSeconds = c.getDouble("train.oil-seconds", 120);
        Material bed = Material.matchMaterial(c.getString("train.track-bed", "GRAVEL"));
        if (bed != null && bed.isBlock()) defaultBed = bed.createBlockData().getAsString();

        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        for (World w : Bukkit.getWorlds()) {
            for (Interaction hb : w.getEntitiesByClass(Interaction.class)) {
                tryRegisterPiece(hb);
                tryRegister(hb, 0);
            }
        }
    }

    void disable() {
        for (Car c : cars.values()) c.saveState();
        cars.clear();
        pieces.clear();
    }

    // =====================================================================
    //  유틸
    // =====================================================================
    void tag(Entity en, String cid, String role, int idx) {
        PersistentDataContainer p = en.getPersistentDataContainer();
        p.set(K_ID, PersistentDataType.STRING, cid);
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

    boolean isTrainEntity(Entity en) {
        return en.getPersistentDataContainer().has(K_ID, PersistentDataType.STRING);
    }

    boolean isPieceHit(Entity en) {
        return en.getPersistentDataContainer().has(K_PIECE, PersistentDataType.STRING);
    }

    Piece pieceOf(Entity en) {
        String s = en.getPersistentDataContainer().get(K_PIECE, PersistentDataType.STRING);
        if (s == null) return null;
        try {
            return pieces.get(UUID.fromString(s.split(";")[0]));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    Car carOf(Entity en) {
        String sid = en.getPersistentDataContainer().get(K_ID, PersistentDataType.STRING);
        if (sid == null) return null;
        try {
            return cars.get(UUID.fromString(sid));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    static boolean passable(Block b) {
        return b.isPassable() || b.getType() == Material.LILY_PAD;
    }

    // ---- 연료 ----
    double burnSeconds(ItemStack it) {
        if (it == null || it.getType().isAir()) return 0;
        Material m = it.getType();
        if (m == Material.COAL || m == Material.CHARCOAL) return coalSeconds;
        if (m == Material.COAL_BLOCK) return coalSeconds * 10;
        if (plugin.is(it, plugin.oilItem)) return oilSeconds;
        return 0;
    }

    boolean isFuel(ItemStack it) {
        return burnSeconds(it) > 0;
    }

    // ---- 아이템 ----
    ItemStack carItem(CarType t) {
        ItemStack it = new ItemStack(t.icon());
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text(t.label(), NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        String desc = t.loco() ? "기차를 끄는 조종칸 (석탄/정제유)" : t.cargo() ? "짐을 싣는 화물칸" : "6명이 앉는 탑승칸";
        m.lore(List.of(
                Component.text(desc, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("철로 위에서 우클릭으로 설치", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("다른 칸 앞/뒤에 놓으면 자동으로 연결", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)));
        m.getPersistentDataContainer().set(K_ITEM, PersistentDataType.STRING, t.key());
        m.setMaxStackSize(1);
        it.setItemMeta(m);
        return it;
    }

    ItemStack trackItem(String bedData, int amount) {
        ItemStack it = new ItemStack(Material.RAIL, amount);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text("철로 (5x1)", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(
                Component.text("블럭 윗면 우클릭: 철로 한 조각 설치 (끝 근처를 누르면 이어서 연장, 지형 따라 경사)", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("곡선: 빈손으로 시작/끝 철로를 차례로 우클릭 -> 사이를 자동 연결", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("웅크리고 설치: 갈림길 / 웅크리고 우클릭: 갈림길 전환 / 웅크리고 때리기: 회수", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)));
        m.getPersistentDataContainer().set(K_ITEM, PersistentDataType.STRING, "track");
        m.getPersistentDataContainer().set(K_TBED, PersistentDataType.STRING, bedData);
        it.setItemMeta(m);
        return it;
    }

    String itemKey(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(K_ITEM, PersistentDataType.STRING);
    }

    // ---- 인벤토리 홀더 ----
    static final class FuelHolder implements InventoryHolder {
        final UUID carId;
        Inventory inv;

        FuelHolder(UUID carId) {
            this.carId = carId;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    static final class CargoHolder implements InventoryHolder {
        final UUID carId;
        final int idx;
        Inventory inv;

        CargoHolder(UUID carId, int idx) {
            this.carId = carId;
            this.idx = idx;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    // =====================================================================
    //  철로 탐색
    // =====================================================================
    /** 철로 위 가장 가까운 지점 */
    record Snap(Piece piece, double t, double dist, double[] pos) {}

    Snap nearestPiece(World w, double x, double y, double z, double maxDist, double maxDy) {
        Snap best = null;
        for (Piece p : pieces.values()) {
            if (p.world != w) continue;
            double dx = p.b[0] - p.a[0], dz = p.b[2] - p.a[2];
            double l2 = dx * dx + dz * dz;
            double t = l2 < 1e-9 ? 0.5 : ((x - p.a[0]) * dx + (z - p.a[2]) * dz) / l2;
            t = Math.max(0, Math.min(1, t));
            double[] q = p.at(t);
            double d = Math.hypot(q[0] - x, q[2] - z);
            if (d > maxDist || Math.abs(q[1] - y) > maxDy) continue;
            if (best == null || d < best.dist()) best = new Snap(p, t, d, q);
        }
        return best;
    }

    /** 이 철로 끝에 이어진(로드된) 철로들. pend[0] = 아직 로드 안 된 이웃이 있음 */
    List<Piece> neighbors(Piece p, int end, boolean[] pend) {
        List<Piece> r = new ArrayList<>();
        Iterator<UUID> it = p.links(end).iterator();
        boolean pruned = false;
        while (it.hasNext()) {
            UUID id = it.next();
            Piece q = pieces.get(id);
            if (q != null) {
                r.add(q);
            } else {
                double[] pt = p.end(end);
                if (!p.world.isChunkLoaded(((int) Math.floor(pt[0])) >> 4, ((int) Math.floor(pt[2])) >> 4)) {
                    if (pend != null) pend[0] = true;
                } else {
                    it.remove();
                    pruned = true;
                }
            }
        }
        if (pruned) p.save();
        return r;
    }

    void link(Piece p, int pe, Piece q, int qe) {
        if (!p.links(pe).contains(q.id)) p.links(pe).add(q.id);
        if (!q.links(qe).contains(p.id)) q.links(qe).add(p.id);
        p.rebuild();
        q.rebuild();
    }

    Piece makePiece(World w, double[] a, double[] b, String bed) {
        UUID id = UUID.randomUUID();
        Piece p = new Piece(id, w, a, b, bed);
        double[] m = p.at(0.5);
        p.hit = w.spawn(new Location(w, m[0], m[1], m[2]), Interaction.class, ent -> {
            ent.setInteractionWidth(1.6f);
            ent.setInteractionHeight(0.3f);
            ent.setResponsive(true);
        });
        pieces.put(id, p);
        p.rebuild();
        return p;
    }

    void tryRegisterPiece(Interaction hb) {
        if (!hb.isValid()) return;
        PersistentDataContainer pc = hb.getPersistentDataContainer();
        String s = pc.get(K_PIECE, PersistentDataType.STRING);
        if (s == null) return;
        try {
            String[] f = s.split(";");
            UUID id = UUID.fromString(f[0]);
            if (pieces.containsKey(id)) return;
            double[] a = parse3(f[1]);
            double[] b = parse3(f[2]);
            String bed = pc.get(K_TBED, PersistentDataType.STRING);
            Piece p = new Piece(id, hb.getWorld(), a, b, bed == null ? defaultBed : bed);
            p.state = Integer.parseInt(f[3]);
            String ln = pc.get(K_PLINK, PersistentDataType.STRING);
            if (ln != null) {
                String[] sp = ln.split("\\|", -1);
                parseLinks(sp.length > 0 ? sp[0] : "", p.la);
                parseLinks(sp.length > 1 ? sp[1] : "", p.lb);
            }
            p.hit = hb;
            pieces.put(id, p);
            p.rebuild();
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("철로 복구 실패: " + ex);
        }
    }

    static double[] parse3(String s) {
        String[] t = s.split(",");
        return new double[]{Double.parseDouble(t[0]), Double.parseDouble(t[1]), Double.parseDouble(t[2])};
    }

    // =====================================================================
    //  칸 객체
    // =====================================================================
    final class Car {
        final UUID id;
        final CarType type;
        final Interaction hull;
        final ArmorStand[] seats;
        final Interaction[] seatHits;
        final Interaction[] hits;
        final ArmorStand[] padCars;
        final Shulker[] pads;
        final double[] padDy;
        final List<BlockDisplay> parts;
        final BlockDisplay[] crates;
        final Inventory fuelInv;
        final Inventory[] cargo;
        final World world;
        double x, y, z;
        float yaw, pitch;
        UUID nextId = null;
        // 머리 칸 전용
        double speed = 0;
        double dist = 0;
        double burn = 0;
        boolean running = false;
        String sig = null;
        final List<double[]> trail = new ArrayList<>();   // {거리, x, y, z, yaw, pitch}
        Piece cp;
        boolean fwd = true;
        double ps;
        boolean located = false;
        boolean inLeft, inRight;
        boolean warned = false, dirty = false;

        Car(UUID id, CarType type, Interaction hull, ArmorStand[] seats, Interaction[] seatHits, Interaction[] hits,
            ArmorStand[] padCars, Shulker[] pads, List<BlockDisplay> parts, BlockDisplay[] crates,
            double x, double y, double z, float yaw) {
            this.id = id;
            this.type = type;
            this.hull = hull;
            this.seats = seats;
            this.seatHits = seatHits;
            this.hits = hits;
            this.padCars = padCars;
            this.pads = pads;
            this.padDy = new double[pads.length];
            this.parts = parts;
            this.crates = crates;
            this.world = hull.getWorld();
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            if (type.loco()) {
                FuelHolder h = new FuelHolder(id);
                h.inv = Bukkit.createInventory(h, InventoryType.FURNACE, Component.text("조종칸 연료", NamedTextColor.DARK_GRAY));
                fuelInv = h.inv;
            } else {
                fuelInv = null;
            }
            if (type.cargo()) {
                cargo = new Inventory[2];
                for (int i = 0; i < 2; i++) {
                    CargoHolder h = new CargoHolder(id, i);
                    h.inv = Bukkit.createInventory(h, 54, Component.text("화물칸 " + (i + 1) + "/2", NamedTextColor.DARK_GRAY));
                    cargo[i] = h.inv;
                }
            } else {
                cargo = new Inventory[0];
            }
        }

        Location origin() {
            return new Location(world, x, y, z, yaw, 0f);
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
                for (ItemStack it : inv.getContents()) if (it != null && !it.getType().isAir()) return false;
            }
            return true;
        }

        // ---- 저장 ----
        void saveState() {
            PersistentDataContainer p = hull.getPersistentDataContainer();
            p.set(K_YAW, PersistentDataType.FLOAT, yaw);
            if (nextId == null) p.remove(K_NEXT);
            else p.set(K_NEXT, PersistentDataType.STRING, nextId.toString());
            p.set(K_BURN, PersistentDataType.DOUBLE, burn);
            p.set(K_RUN, PersistentDataType.BYTE, (byte) (running ? 1 : 0));
            if (fuelInv != null) {
                p.set(K_FUEL, PersistentDataType.BYTE_ARRAY, ItemStack.serializeItemsAsBytes(fuelInv.getContents()));
            }
            saveCargo();
        }

        void saveCargo() {
            PersistentDataContainer p = hull.getPersistentDataContainer();
            if (cargo.length == 2) {
                p.set(K_C0, PersistentDataType.BYTE_ARRAY, ItemStack.serializeItemsAsBytes(cargo[0].getContents()));
                p.set(K_C1, PersistentDataType.BYTE_ARRAY, ItemStack.serializeItemsAsBytes(cargo[1].getContents()));
            }
        }

        void loadState() {
            PersistentDataContainer p = hull.getPersistentDataContainer();
            try {
                byte[] f = p.get(K_FUEL, PersistentDataType.BYTE_ARRAY);
                if (fuelInv != null && f != null && f.length > 0) fuelInv.setContents(ItemStack.deserializeItemsFromBytes(f));
                if (cargo.length == 2) {
                    byte[] a = p.get(K_C0, PersistentDataType.BYTE_ARRAY);
                    byte[] b = p.get(K_C1, PersistentDataType.BYTE_ARRAY);
                    if (a != null && a.length > 0) cargo[0].setContents(ItemStack.deserializeItemsFromBytes(a));
                    if (b != null && b.length > 0) cargo[1].setContents(ItemStack.deserializeItemsFromBytes(b));
                }
            } catch (Exception ex) {
                plugin.getLogger().warning("기차 내용물 복구 실패: " + ex.getMessage());
            }
            Double bv = p.get(K_BURN, PersistentDataType.DOUBLE);
            burn = bv == null ? 0 : bv;
            Byte rv = p.get(K_RUN, PersistentDataType.BYTE);
            running = rv != null && rv == 1;
            String ns = p.get(K_NEXT, PersistentDataType.STRING);
            if (ns != null) {
                try {
                    nextId = UUID.fromString(ns);
                } catch (IllegalArgumentException ignored) {
                    nextId = null;
                }
            }
        }

        // ---- 화물 표시 ----
        void updateCrates() {
            if (crates.length == 0) return;
            List<ItemStack> stacks = new ArrayList<>();
            for (Inventory inv : cargo) {
                for (ItemStack it : inv.getContents()) if (it != null && !it.getType().isAir()) stacks.add(it);
            }
            int n = 0;
            if (!stacks.isEmpty()) {
                n = (int) Math.ceil(stacks.size() / 108.0 * CRATES);
                n = Math.max(1, Math.min(CRATES, n));
            }
            for (int i = 0; i < crates.length; i++) {
                BlockDisplay d = crates[i];
                if (d == null || !d.isValid()) continue;
                if (i < n) {
                    ItemStack s = stacks.get(Math.min(stacks.size() - 1, i * stacks.size() / n));
                    Material m = s.getType();
                    boolean solid = m.isBlock() && m.isSolid() && m.isOccluding();
                    d.setBlock(solid ? m.createBlockData() : Bukkit.createBlockData("minecraft:barrel[facing=up]"));
                } else {
                    d.setBlock(Bukkit.createBlockData(Material.AIR));
                }
            }
        }

        // ---- 탑승 ----
        void mount(Player p, int want) {
            if (p.isInsideVehicle()) return;
            if (seats.length == 0) return;
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
            if (type.loco()) p.sendActionBar(Component.text("운전석 - W 출발(이후 자동 주행) / S 정지 / 갈림길 직전 A,D 선택", NamedTextColor.GOLD));
        }

        // ---- 이동 ----
        double padBottom(int i) {
            float[] pd = type.pads()[i];
            double[] o = rot3(yaw, pitch, pd[0], FLOOR, pd[1]);
            return y + o[1] - (pd[3] > 0.5f ? 0 : pd[2]) - padDy[i];
        }

        void moveAll() {
            Location base = new Location(world, x, y, z, yaw, pitch);
            hull.teleport(new Location(world, x, y, z, yaw, 0f));
            for (int i = 0; i < seats.length; i++) {
                double[] o = rot3(yaw, pitch, type.seats()[i][0], FLOOR + SEAT_UP, type.seats()[i][1]);
                seats[i].teleport(new Location(world, x + o[0], y + o[1], z + o[2], yaw, 0f),
                        TeleportFlag.EntityState.RETAIN_PASSENGERS);
                double[] h = rot3(yaw, pitch, type.seats()[i][0], FLOOR, type.seats()[i][1]);
                seatHits[i].teleport(new Location(world, x + h[0], y + h[1], z + h[2]));
            }
            for (int i = 0; i < hits.length; i++) {
                double[] o = type.loco() ? rot3(yaw, pitch, 0, FLOOR, 1.9) : rot3(yaw, pitch, i == 0 ? -1.25 : 1.25, FLOOR, 0);
                hits[i].teleport(new Location(world, x + o[0], y + o[1], z + o[2]));
            }
            for (int i = 0; i < pads.length; i++) {
                double[] o = rot(yaw, type.pads()[i][0], rot3(0, pitch, 0, 0, type.pads()[i][1])[2]);
                padCars[i].teleport(new Location(world, x + o[0], padBottom(i), z + o[1], yaw, 0f),
                        TeleportFlag.EntityState.RETAIN_PASSENGERS);
            }
            for (BlockDisplay d : parts) d.teleport(base);
            for (BlockDisplay d : crates) if (d != null) d.teleport(base);
        }

        void calibrate() {
            for (int i = 0; i < pads.length; i++) {
                if (!pads[i].isValid() || !padCars[i].isValid()) continue;
                double d = pads[i].getY() - padCars[i].getY();
                if (Math.abs(d) < 4.0) padDy[i] = d;
            }
            moveAll();
        }

        void carry(double ox, double oz, float oyaw) {
            double orad = Math.toRadians(oyaw);
            double oc = Math.cos(orad);
            double os = Math.sin(orad);
            double nrad = Math.toRadians(yaw);
            double nc = Math.cos(nrad);
            double ns = Math.sin(nrad);
            double pr = Math.toRadians(pitch);
            for (Player pl : world.getNearbyPlayers(new Location(world, ox, y, oz), 6.0)) {
                if (pl.isInsideVehicle() || !pl.isOnGround()) continue;
                Location pp = pl.getLocation();
                double wx = pp.getX() - ox;
                double wz = pp.getZ() - oz;
                double lx = wx * oc + wz * os;
                double lz = -wx * os + wz * oc;
                if (Math.abs(lx) > 2.8 || Math.abs(lz) > 3.2) continue;
                double deckY = y + FLOOR * Math.cos(pr) - lz * Math.sin(pr);
                double dy = pp.getY() - deckY;
                if (dy < -1.2 || dy > 1.2) continue;
                if (pl.getCurrentInput().isJump()) continue;
                double nx = x + lx * nc - lz * ns;
                double nz = z + lx * ns + lz * nc;
                double vy = Math.max(-0.4, Math.min(0.4, deckY - pp.getY()));
                pl.setVelocity(new Vector(nx - pp.getX(), vy, nz - pp.getZ()));
            }
        }

        void remove() {
            for (ArmorStand st : seats) {
                for (Entity en : new ArrayList<>(st.getPassengers())) st.removePassenger(en);
                st.remove();
            }
            for (Interaction i : seatHits) i.remove();
            for (Interaction i : hits) i.remove();
            for (Shulker sk : pads) sk.remove();
            for (ArmorStand pc : padCars) {
                for (Entity en : new ArrayList<>(pc.getPassengers())) pc.removePassenger(en);
                pc.remove();
            }
            for (BlockDisplay d : parts) d.remove();
            for (BlockDisplay d : crates) if (d != null) d.remove();
            hull.remove();
        }

        // ---- 연료 ----
        boolean ignite() {
            if (fuelInv == null) return false;
            ItemStack s = fuelInv.getItem(1);
            double sec = burnSeconds(s);
            if (sec <= 0) return false;
            s.setAmount(s.getAmount() - 1);
            fuelInv.setItem(1, s.getAmount() <= 0 ? null : s);
            burn = sec * 20;
            dirty = true;
            world.playSound(origin(), Sound.BLOCK_FURNACE_FIRE_CRACKLE, 1f, 1f);
            return true;
        }

        int fuelItems() {
            if (fuelInv == null) return 0;
            ItemStack s = fuelInv.getItem(1);
            return s == null ? 0 : s.getAmount();
        }

        // ---- 궤적(뒤 칸들이 따라오는 길) ----
        void rebuildTrail(List<Car> chain) {
            trail.clear();
            dist = 0;
            located = false;
            for (int k = chain.size() - 1; k >= 0; k--) {
                Car c = chain.get(k);
                trail.add(new double[]{-k * SPACING, c.x, c.y, c.z, c.yaw, c.pitch});
            }
        }

        double[] sample(double d) {
            if (trail.isEmpty()) return new double[]{x, y, z, yaw, pitch};
            double[] first = trail.get(0);
            if (d <= first[0]) return new double[]{first[1], first[2], first[3], first[4], first[5]};
            for (int i = 1; i < trail.size(); i++) {
                double[] a = trail.get(i - 1);
                double[] b = trail.get(i);
                if (d <= b[0]) {
                    double span = b[0] - a[0];
                    double t = span <= 1e-9 ? 1 : (d - a[0]) / span;
                    double dy = norm180(b[4] - a[4]);
                    return new double[]{a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t,
                            a[3] + (b[3] - a[3]) * t, a[4] + dy * t, a[5] + (b[5] - a[5]) * t};
                }
            }
            double[] last = trail.get(trail.size() - 1);
            return new double[]{last[1], last[2], last[3], last[4], last[5]};
        }

        // ---- 철로 위 위치 ----
        boolean locate() {
            Snap s = nearestPiece(world, x, y - TILE_TOP, z, 1.6, 1.5);
            if (s == null) return false;
            cp = s.piece();
            double dyaw = norm180(cp.yawFwd() - yaw);
            fwd = Math.abs(dyaw) <= 90;
            ps = (fwd ? s.t() : 1 - s.t()) * cp.len();
            located = true;
            return true;
        }

        void halt(boolean stopRunning) {
            speed = 0;
            if (stopRunning && running) {
                running = false;
                dirty = true;
            }
        }

        /** 현재 철로의 끝에서 다음 철로로 넘어감 */
        boolean enterNext() {
            int exitEnd = fwd ? 1 : 0;
            boolean[] pend = new boolean[1];
            List<Piece> cand = new ArrayList<>(neighbors(cp, exitEnd, pend));
            cand.remove(cp);
            if (cand.isEmpty()) {
                halt(!pend[0]);
                return false;
            }
            double[] ex = cp.end(exitEnd);
            double[] oth = cp.end(1 - exitEnd);
            double fx = ex[0] - oth[0], fz = ex[2] - oth[2];
            Piece n;
            if (cand.size() == 1) {
                n = cand.get(0);
            } else {
                // 왼쪽 -> 오른쪽 순서로 정렬
                cand.sort(Comparator.comparingDouble((Piece q) -> -leftness(q, ex, fx, fz)));
                int pick;
                if (inLeft && !inRight) pick = 0;
                else if (inRight && !inLeft) pick = cand.size() - 1;
                else pick = Math.min(cp.state, cand.size() - 1);
                n = cand.get(pick);
            }
            double da = sq(n.a[0] - ex[0]) + sq(n.a[2] - ex[2]);
            double db = sq(n.b[0] - ex[0]) + sq(n.b[2] - ex[2]);
            cp = n;
            fwd = da <= db;
            ps = 0;
            return true;
        }

        double leftness(Piece q, double[] ex, double fx, double fz) {
            double da = sq(q.a[0] - ex[0]) + sq(q.a[2] - ex[2]);
            double db = sq(q.b[0] - ex[0]) + sq(q.b[2] - ex[2]);
            double[] far = da <= db ? q.b : q.a;
            double vx = far[0] - ex[0], vz = far[2] - ex[2];
            double l = Math.hypot(fx, fz);
            if (l < 1e-6) return 0;
            return (vx * fz - vz * fx) / l;
        }

        double advance(double ds) {
            double moved = 0;
            int guard = 0;
            while (ds > 1e-9 && guard++ < 10) {
                double len = cp.len();
                double rem = len - ps;
                if (ds < rem) {
                    ps += ds;
                    moved += ds;
                    ds = 0;
                } else {
                    ds -= rem;
                    moved += rem;
                    ps = len;
                    if (!enterNext()) break;
                }
            }
            return moved;
        }

        void driveHead(List<Car> chain) {
            Player drv = type.loco() ? rider(0) : null;
            boolean w = false, back = false;
            inLeft = false;
            inRight = false;
            if (drv != null) {
                Input in = drv.getCurrentInput();
                w = in.isForward();
                back = in.isBackward();
                inLeft = in.isLeft();
                inRight = in.isRight();
                if (w && !back && !running) {
                    running = true;
                    dirty = true;
                } else if (back && running) {
                    running = false;
                    dirty = true;
                }
            }
            if (!type.loco()) return;
            if (!located && !locate()) {
                speed = 0;
                if (drv != null && tickCount % 40 == 0) {
                    drv.sendActionBar(Component.text("철로 위가 아닙니다.", NamedTextColor.RED));
                }
                return;
            }
            boolean throttle = running;
            if (throttle && burn <= 0 && !ignite()) {
                throttle = false;
                running = false;
                dirty = true;
                Location l = origin();
                world.playSound(l, Sound.BLOCK_FIRE_EXTINGUISH, 1f, 1f);
                for (Player pl : world.getNearbyPlayers(l, 20)) {
                    pl.sendActionBar(Component.text("연료가 떨어졌습니다! 조종칸 앞쪽 화로에 석탄/정제유를 넣으세요.", NamedTextColor.RED));
                }
            }
            // 속도 제한: 급한 곡선/오르막은 느리게
            double turnAmt = 0;
            double pitchAbs = Math.abs(cp.pitchFwd());
            double cap = maxSpeed;
            if (pitchAbs > 20) cap = Math.min(cap, maxSpeed * 0.7);
            if (maxSpeed > curveSpeed && turnAmount(cp) > 6) cap = Math.min(cap, curveSpeed);
            if (throttle) {
                burn -= 1;
                dirty = true;
                if (speed < cap) speed = Math.min(cap, speed + accel);
                else speed = Math.max(cap, speed - 0.01);
            } else if (back) {
                speed = Math.max(0, speed - brake);
            } else {
                speed = Math.max(0, speed * 0.99 - 0.003);
            }
            if (speed < 0.003) speed = 0;

            if (drv != null && tickCount % 10 == 0) {
                NamedTextColor col = burn <= 0 && fuelItems() == 0 ? NamedTextColor.RED : NamedTextColor.GOLD;
                drv.sendActionBar(Component.text((running ? "주행 중" : "정지") + "  |  " + (int) Math.round(speed * 72)
                        + " km/h  |  연료 " + (int) Math.ceil(burn / 20) + "초 + 대기 " + fuelItems() + "개  |  " + chain.size() + "칸", col));
            }

            if (speed == 0) {
                if (dirty) {
                    saveState();
                    dirty = false;
                }
                return;
            }

            double moved = advance(speed);
            if (moved <= 0) return;
            double t = cp.len() <= 0 ? 0 : ps / cp.len();
            double[] pos = cp.at(fwd ? t : 1 - t);
            double ny = fwd ? cp.yawFwd() : cp.yawFwd() + 180;
            double np = fwd ? cp.pitchFwd() : -cp.pitchFwd();

            int n = chain.size();
            double[] ox = new double[n], oz = new double[n];
            float[] oy = new float[n];
            for (int i = 0; i < n; i++) {
                ox[i] = chain.get(i).x;
                oz[i] = chain.get(i).z;
                oy[i] = chain.get(i).yaw;
            }
            dist += moved;
            x = pos[0];
            z = pos[2];
            y = pos[1] + TILE_TOP;
            yaw = (float) norm180(ny);
            pitch = (float) np;
            trail.add(new double[]{dist, x, y, z, yaw, pitch});
            double keep = dist - (n - 1) * SPACING - 2;
            while (trail.size() > 2 && trail.get(1)[0] < keep) trail.remove(0);
            moveAll();
            carry(ox[0], oz[0], oy[0]);
            for (int i = 1; i < n; i++) {
                Car c = chain.get(i);
                double[] s = sample(dist - i * SPACING);
                c.x = s[0];
                c.y = s[1];
                c.z = s[2];
                c.yaw = (float) s[3];
                c.pitch = (float) s[4];
                c.moveAll();
                c.carry(ox[i], oz[i], oy[i]);
            }
            dirty = true;
        }

        /** 이 철로 조각 다음에 이어지는 방향 꺾임(도) - 속도 제한용 */
        double turnAmount(Piece p) {
            int e = fwd ? 1 : 0;
            List<Piece> nb = neighbors(p, e, null);
            for (Piece q : nb) {
                if (q == p) continue;
                double da = sq(q.a[0] - p.end(e)[0]) + sq(q.a[2] - p.end(e)[2]);
                double db = sq(q.b[0] - p.end(e)[0]) + sq(q.b[2] - p.end(e)[2]);
                double qy = da <= db ? q.yawFwd() : q.yawFwd() + 180;
                double py = fwd ? p.yawFwd() : p.yawFwd() + 180;
                return Math.abs(norm180(qy - py));
            }
            return 0;
        }
    }

    // =====================================================================
    //  칸 생성 / 복구
    // =====================================================================
    Car spawnCar(CarType t, Location at, float yaw) {
        UUID id = UUID.randomUUID();
        String cid = id.toString();
        World w = at.getWorld();
        double x = at.getX(), y = at.getY(), z = at.getZ();
        Location base = new Location(w, x, y, z, yaw, 0f);

        Interaction hull = w.spawn(base, Interaction.class, ent -> {
            ent.setInteractionWidth(5f);
            ent.setInteractionHeight(0.5f);
            ent.setResponsive(true);
            tag(ent, cid, "hull", 0);
            ent.getPersistentDataContainer().set(K_TYPE, PersistentDataType.STRING, t.key());
            ent.getPersistentDataContainer().set(K_YAW, PersistentDataType.FLOAT, yaw);
        });

        int ns = t.seats().length;
        ArmorStand[] seats = new ArmorStand[ns];
        Interaction[] seatHits = new Interaction[ns];
        for (int i = 0; i < ns; i++) {
            final int idx = i;
            double[] o = rot(yaw, t.seats()[i][0], t.seats()[i][1]);
            seats[i] = w.spawn(new Location(w, x + o[0], y + FLOOR + SEAT_UP, z + o[1], yaw, 0f), ArmorStand.class, a -> {
                a.setVisible(false);
                a.setGravity(false);
                a.setMarker(true);
                a.setSmall(true);
                a.setInvulnerable(true);
                a.setSilent(true);
                tag(a, cid, "seat", idx);
            });
            seatHits[i] = w.spawn(new Location(w, x + o[0], y + FLOOR, z + o[1]), Interaction.class, h -> {
                h.setInteractionWidth(0.9f);
                h.setInteractionHeight(1.3f);
                h.setResponsive(true);
                tag(h, cid, "seathit", idx);
            });
        }

        int nh = t.loco() ? 1 : t.cargo() ? 2 : 0;
        Interaction[] hits = new Interaction[nh];
        for (int i = 0; i < nh; i++) {
            final int idx = i;
            double[] o = t.loco() ? rot(yaw, 0, 1.9) : rot(yaw, i == 0 ? -1.25 : 1.25, 0);
            hits[i] = w.spawn(new Location(w, x + o[0], y + FLOOR, z + o[1]), Interaction.class, h -> {
                h.setInteractionWidth(t.loco() ? 3.6f : 2.4f);
                h.setInteractionHeight(t.loco() ? 1.8f : 1.6f);
                h.setResponsive(true);
                tag(h, cid, t.loco() ? "fuelhit" : "cargohit", idx);
            });
        }

        int np = t.pads().length;
        ArmorStand[] padCars = new ArmorStand[np];
        Shulker[] pads = new Shulker[np];
        for (int i = 0; i < np; i++) {
            final int idx = i;
            final float sc = t.pads()[i][2];
            final boolean wall = t.pads()[i][3] > 0.5f;
            double[] o = rot(yaw, t.pads()[i][0], t.pads()[i][1]);
            double py = y + FLOOR - (wall ? 0 : sc);
            padCars[i] = w.spawn(new Location(w, x + o[0], py, z + o[1], yaw, 0f), ArmorStand.class, a -> {
                a.setVisible(false);
                a.setGravity(false);
                a.setMarker(true);
                a.setSmall(true);
                a.setInvulnerable(true);
                a.setSilent(true);
                tag(a, cid, "padcar", idx);
            });
            pads[i] = w.spawn(new Location(w, x + o[0], py, z + o[1], yaw, 0f), Shulker.class, sk -> {
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
                tag(sk, cid, "pad", idx);
            });
            padCars[i].addPassenger(pads[i]);
        }

        List<BlockDisplay> parts = new ArrayList<>();
        for (int i = 0; i < t.parts().size(); i++) {
            final Part pt = t.parts().get(i);
            final int idx = i;
            parts.add(w.spawn(base, BlockDisplay.class, d -> {
                d.setBlock(Bukkit.createBlockData(pt.data()));
                d.setTransformation(ShipManager.transformOf(pt));
                d.setTeleportDuration(3);
                d.setViewRange(2.0f);
                tag(d, cid, "part", idx);
            }));
        }

        BlockDisplay[] crates = new BlockDisplay[t.cargo() ? CRATES : 0];
        for (int i = 0; i < crates.length; i++) {
            final int idx = i;
            int layer = i / 8;
            int k = i % 8;
            float cx = (k % 2 == 0 ? -0.95f : 0.95f);
            float cz = -2.1f + (k / 2) * 1.4f;
            float cy = FLOOR + 0.65f + layer * 1.3f;
            final Part pt = box("minecraft:air", cx, cy, cz, 1.3f, 1.3f, 1.3f);
            crates[i] = w.spawn(base, BlockDisplay.class, d -> {
                d.setBlock(Bukkit.createBlockData(Material.AIR));
                d.setTransformation(ShipManager.transformOf(pt));
                d.setTeleportDuration(3);
                d.setViewRange(2.0f);
                tag(d, cid, "crate", idx);
            });
        }

        Car car = new Car(id, t, hull, seats, seatHits, hits, padCars, pads, parts, crates, x, y, z, yaw);
        cars.put(id, car);
        car.saveState();
        Bukkit.getScheduler().runTaskLater(plugin, car::calibrate, 3L);
        return car;
    }

    void tryRegister(Interaction hb, int attempt) {
        if (!hb.isValid()) return;
        PersistentDataContainer hp = hb.getPersistentDataContainer();
        if (!"hull".equals(hp.get(K_ROLE, PersistentDataType.STRING))) return;
        String cid = hp.get(K_ID, PersistentDataType.STRING);
        String tk = hp.get(K_TYPE, PersistentDataType.STRING);
        if (cid == null || tk == null) return;
        CarType t = types.get(tk);
        if (t == null) return;
        UUID id;
        try {
            id = UUID.fromString(cid);
        } catch (IllegalArgumentException ex) {
            return;
        }
        if (cars.containsKey(id)) return;

        ArmorStand[] seats = new ArmorStand[t.seats().length];
        Interaction[] seatHits = new Interaction[t.seats().length];
        Interaction[] hits = new Interaction[t.loco() ? 1 : t.cargo() ? 2 : 0];
        ArmorStand[] padCars = new ArmorStand[t.pads().length];
        Shulker[] pads = new Shulker[t.pads().length];
        List<BlockDisplay> parts = new ArrayList<>();
        BlockDisplay[] crates = new BlockDisplay[t.cargo() ? CRATES : 0];
        for (Entity en : hb.getNearbyEntities(9, 8, 9)) {
            PersistentDataContainer p = en.getPersistentDataContainer();
            if (!cid.equals(p.get(K_ID, PersistentDataType.STRING))) continue;
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
                case "fuelhit", "cargohit" -> {
                    if (en instanceof Interaction h && idx >= 0 && idx < hits.length) hits[idx] = h;
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
                case "crate" -> {
                    if (en instanceof BlockDisplay d && idx >= 0 && idx < crates.length) crates[idx] = d;
                }
                default -> {
                }
            }
        }
        boolean complete = parts.size() == t.parts().size();
        for (ArmorStand a : seats) if (a == null) complete = false;
        for (Interaction h : seatHits) if (h == null) complete = false;
        for (Interaction h : hits) if (h == null) complete = false;
        for (ArmorStand a : padCars) if (a == null) complete = false;
        for (Shulker sk : pads) if (sk == null) complete = false;
        for (BlockDisplay d : crates) if (d == null) complete = false;
        if (!complete) {
            if (attempt < 5) {
                final int next = attempt + 1;
                Bukkit.getScheduler().runTaskLater(plugin, () -> tryRegister(hb, next), 20L);
            }
            return;
        }
        Float sy = hp.get(K_YAW, PersistentDataType.FLOAT);
        float yaw = sy == null ? hb.getLocation().getYaw() : sy;
        Car car = new Car(id, t, hb, seats, seatHits, hits, padCars, pads, parts, crates,
                hb.getX(), hb.getY(), hb.getZ(), yaw);
        car.loadState();
        cars.put(id, car);
        car.moveAll();
        car.updateCrates();
        Bukkit.getScheduler().runTaskLater(plugin, car::calibrate, 3L);
    }



    // =====================================================================
    //  틱
    // =====================================================================
    List<Car> chainFrom(Car head) {
        List<Car> l = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        Car c = head;
        while (c != null && seen.add(c.id)) {
            l.add(c);
            c = c.nextId == null ? null : cars.get(c.nextId);
        }
        return l;
    }

    void tick() {
        tickCount++;
        Iterator<Car> it = cars.values().iterator();
        Set<UUID> hasPrev = new HashSet<>();
        while (it.hasNext()) {
            Car c = it.next();
            if (!c.hull.isValid()) {
                it.remove();
                continue;
            }
            if (c.nextId != null && cars.containsKey(c.nextId)) hasPrev.add(c.nextId);
        }
        for (Car c : new ArrayList<>(cars.values())) {
            if (hasPrev.contains(c.id)) {
                c.sig = null;
                continue;
            }
            try {
                List<Car> chain = chainFrom(c);
                StringBuilder sb = new StringBuilder();
                for (Car k : chain) sb.append(k.id).append(',');
                String sig = sb.toString();
                if (!sig.equals(c.sig)) {
                    c.rebuildTrail(chain);
                    c.sig = sig;
                }
                c.driveHead(chain);
            } catch (Exception ex) {
                if (!c.warned) {
                    c.warned = true;
                    plugin.getLogger().warning("기차 처리 중 오류: " + ex);
                }
            }
        }
    }

    // =====================================================================
    //  설치
    // =====================================================================
    @EventHandler
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = e.getItem();
        if (itemKey(item) == null) return;
        e.setCancelled(true);
        tryPlace(e.getPlayer(), item);
    }

    void tryPlace(Player p, ItemStack item) {
        Long last = lastPlace.get(p.getUniqueId());
        if (last != null && last == tickCount) return;
        lastPlace.put(p.getUniqueId(), tickCount);
        String key = itemKey(item);
        if (key == null) return;
        if (key.equals("track")) {
            placeTrack(p, item);
            return;
        }
        CarType t = types.get(key);
        if (t != null) placeCar(p, item, t);
    }

    /** 지면 높이(윗면) 찾기: 기준 높이 위아래 3칸 안에서 위에서부터 처음 만나는 단단한 블럭 */
    static double surfaceY(World w, int bx, int bz, double refY) {
        int top = (int) Math.floor(refY) + 3;
        int bot = (int) Math.floor(refY) - 3;
        for (int yy = top; yy >= bot; yy--) {
            Block b = w.getBlockAt(bx, yy, bz);
            if (!b.isPassable() && !b.isLiquid()) return yy + 1;
        }
        return Double.NaN;
    }

    /** 지점 (x, y, z) 에서 폭 5 x 높이 3 이 비어 있는지. 막힌 위치 문자열 또는 null */
    String clearance(World w, double x, double y, double z, double yawDeg) {
        int by = (int) Math.floor(y + 0.05);
        for (int lx = -2; lx <= 2; lx++) {
            double[] o = rot(yawDeg, lx, 0);
            int bx = (int) Math.floor(x + o[0]);
            int bz = (int) Math.floor(z + o[1]);
            if (!w.isChunkLoaded(bx >> 4, bz >> 4)) return "로드되지 않은 곳";
            for (int dy = 0; dy < 3; dy++) {
                if (!passable(w.getBlockAt(bx, by + dy, bz))) return bx + ", " + (by + dy) + ", " + bz;
            }
        }
        return null;
    }

    boolean overlaps(World w, double[] m, Piece ignore) {
        for (Piece q : pieces.values()) {
            if (q.world != w || q == ignore) continue;
            double[] c = q.at(0.5);
            if (Math.hypot(c[0] - m[0], c[2] - m[2]) < 0.45 && Math.abs(c[1] - m[1]) < 1.5) return true;
        }
        return false;
    }

    void placeTrack(Player p, ItemStack item) {
        RayTraceResult r = p.rayTraceBlocks(8.0);
        Block b = r == null ? null : r.getHitBlock();
        if (b == null || r.getHitBlockFace() != BlockFace.UP) {
            p.sendActionBar(Component.text("블럭의 윗면을 바라보고 사용하세요.", NamedTextColor.RED));
            return;
        }
        World w = b.getWorld();
        Vector hp = r.getHitPosition();
        String bed = item.getItemMeta().getPersistentDataContainer().get(K_TBED, PersistentDataType.STRING);
        if (bed == null) bed = defaultBed;
        boolean sneak = p.isSneaking();

        // 가까운 철로 끝이 있으면 거기서 이어서 연장
        Piece nearP = null;
        int nearE = 0;
        double bd = 2.6;
        for (Piece q : pieces.values()) {
            if (q.world != w) continue;
            for (int e = 0; e < 2; e++) {
                double[] pt = q.end(e);
                double d = Math.hypot(pt[0] - hp.getX(), pt[2] - hp.getZ());
                if (d < bd && Math.abs(pt[1] - hp.getY()) < 2.5) {
                    bd = d;
                    nearP = q;
                    nearE = e;
                }
            }
        }

        double[] na, nb;
        Piece from = null;
        int fromEnd = 0;
        if (nearP != null) {
            boolean[] pend = new boolean[1];
            boolean taken = !neighbors(nearP, nearE, pend).isEmpty() || pend[0];
            if (taken && !sneak) {
                p.sendActionBar(Component.text("이미 이어진 끝입니다. 웅크리고 설치하면 갈림길이 됩니다.", NamedTextColor.RED));
                return;
            }
            double[] s = nearP.end(nearE);
            double[] o = nearP.end(1 - nearE);
            double dx = s[0] - o[0], dz = s[2] - o[2];
            double hl = Math.hypot(dx, dz);
            dx /= hl;
            dz /= hl;
            if (taken) {   // 갈림길: 시선 쪽으로 30도 꺾음
                double lx = -Math.sin(Math.toRadians(p.getLocation().getYaw()));
                double lz = Math.cos(Math.toRadians(p.getLocation().getYaw()));
                double cross = dx * lz - dz * lx;
                double ang = Math.toRadians(cross >= 0 ? 30 : -30);
                double ndx = dx * Math.cos(ang) - dz * Math.sin(ang);
                double ndz = dx * Math.sin(ang) + dz * Math.cos(ang);
                dx = ndx;
                dz = ndz;
            }
            double ex = s[0] + dx;
            double ez = s[2] + dz;
            double sy = surfaceY(w, (int) Math.floor(ex), (int) Math.floor(ez), s[1]);
            double ey = (!Double.isNaN(sy) && Math.abs(sy - s[1]) <= 1.01) ? sy : s[1];
            na = new double[]{s[0], s[1], s[2]};
            nb = new double[]{ex, ey, ez};
            from = nearP;
            fromEnd = nearE;
        } else {
            double yawR = Math.toRadians(Math.round(p.getLocation().getYaw() / 90f) * 90f);
            double dx = -Math.round(Math.sin(yawR));
            double dz = Math.round(Math.cos(yawR));
            double cx = b.getX() + 0.5, cz = b.getZ() + 0.5, cy = b.getY() + 1;
            na = new double[]{cx - dx / 2, cy, cz - dz / 2};
            nb = new double[]{cx + dx / 2, cy, cz + dz / 2};
        }
        double[] mid = {(na[0] + nb[0]) / 2, (na[1] + nb[1]) / 2, (na[2] + nb[2]) / 2};
        if (overlaps(w, mid, null)) {
            p.sendActionBar(Component.text("이미 철로가 있는 자리입니다.", NamedTextColor.RED));
            return;
        }
        double yawDeg = Math.toDegrees(Math.atan2(-(nb[0] - na[0]), nb[2] - na[2]));
        String blocked = clearance(w, nb[0], Math.max(na[1], nb[1]), nb[2], yawDeg);
        if (blocked != null) {
            p.sendActionBar(Component.text("철로 위(폭 5 x 높이 3)가 막혀 있습니다: " + blocked, NamedTextColor.RED));
            return;
        }
        Piece np = makePiece(w, na, nb, bed);
        if (from != null) link(from, fromEnd, np, 0);
        w.playSound(new Location(w, mid[0], mid[1], mid[2]), Sound.BLOCK_GRAVEL_PLACE, 1f, 0.8f);
        if (p.getGameMode() != GameMode.CREATIVE) item.setAmount(item.getAmount() - 1);
    }

    // ---- 곡선: 시작/끝 철로 선택 -> 사이를 이음 ----
    void selectPiece(Player p, Piece piece) {
        UUID first = selected.get(p.getUniqueId());
        if (first == null || pieces.get(first) == null) {
            selected.put(p.getUniqueId(), piece.id);
            p.sendActionBar(Component.text("시작 철로를 선택했습니다. 이어서 끝 철로를 우클릭하세요. (같은 철로를 다시 누르면 취소)", NamedTextColor.GREEN));
            return;
        }
        if (first.equals(piece.id)) {
            selected.remove(p.getUniqueId());
            p.sendActionBar(Component.text("선택을 취소했습니다.", NamedTextColor.YELLOW));
            return;
        }
        selected.remove(p.getUniqueId());
        buildCurve(p, pieces.get(first), piece);
    }

    int countTrack(Player p) {
        int n = 0;
        for (ItemStack it : p.getInventory().getContents()) {
            if ("track".equals(itemKey(it))) n += it.getAmount();
        }
        return n;
    }

    String takeTrack(Player p, int n) {
        String bed = null;
        for (ItemStack it : p.getInventory().getContents()) {
            if (n <= 0) break;
            if (!"track".equals(itemKey(it))) continue;
            if (bed == null) bed = it.getItemMeta().getPersistentDataContainer().get(K_TBED, PersistentDataType.STRING);
            int t = Math.min(n, it.getAmount());
            it.setAmount(it.getAmount() - t);
            n -= t;
        }
        return bed;
    }

    boolean hasOpenEnd(Piece q, int e) {
        boolean[] pend = new boolean[1];
        return neighbors(q, e, pend).isEmpty() && !pend[0];
    }

    void buildCurve(Player p, Piece s, Piece e) {
        if (s.world != e.world) {
            p.sendActionBar(Component.text("서로 다른 월드입니다.", NamedTextColor.RED));
            return;
        }
        int bs = -1, be = -1;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < 2; i++) {
            if (!hasOpenEnd(s, i)) continue;
            for (int j = 0; j < 2; j++) {
                if (!hasOpenEnd(e, j)) continue;
                double d = Math.hypot(s.end(i)[0] - e.end(j)[0], s.end(i)[2] - e.end(j)[2]);
                if (d < bd) {
                    bd = d;
                    bs = i;
                    be = j;
                }
            }
        }
        if (bs < 0) {
            p.sendActionBar(Component.text("두 철로 모두 이어지지 않은 빈 끝이 있어야 합니다.", NamedTextColor.RED));
            return;
        }
        double[] p0 = s.end(bs), p1 = e.end(be);
        double[] o0 = s.end(1 - bs), o1 = e.end(1 - be);
        // 바깥쪽 진행 방향 (수평)
        double t0x = p0[0] - o0[0], t0z = p0[2] - o0[2];
        double l0 = Math.hypot(t0x, t0z);
        t0x /= l0;
        t0z /= l0;
        double t1x = o1[0] - p1[0], t1z = o1[2] - p1[2];   // 끝 철로로 들어가는 방향
        double l1 = Math.hypot(t1x, t1z);
        t1x /= l1;
        t1z /= l1;
        double cx = p1[0] - p0[0], cz = p1[2] - p0[2];
        double D = Math.hypot(cx, cz);
        if (D < 1.2) {
            p.sendActionBar(Component.text("두 철로가 너무 가깝습니다. (직접 이어서 설치하세요)", NamedTextColor.RED));
            return;
        }
        double c0 = (t0x * cx + t0z * cz) / D;
        double c1 = (t1x * cx + t1z * cz) / D;
        if (c0 < 0.2 || c1 < 0.2) {
            p.sendActionBar(Component.text("방향이 맞지 않습니다. 시작 철로 끝이 끝 철로 쪽을 향하고, 끝 철로가 같은 방향으로 이어져야 합니다.", NamedTextColor.RED));
            return;
        }
        // 에르미트 곡선
        double m = D * 1.1;
        int N = 80;
        double[][] pts = new double[N + 1][2];
        double[] acc = new double[N + 1];
        for (int i = 0; i <= N; i++) {
            double u = (double) i / N;
            double h00 = 2 * u * u * u - 3 * u * u + 1, h10 = u * u * u - 2 * u * u + u;
            double h01 = -2 * u * u * u + 3 * u * u, h11 = u * u * u - u * u;
            pts[i][0] = h00 * p0[0] + h10 * m * t0x + h01 * p1[0] + h11 * m * t1x;
            pts[i][1] = h00 * p0[2] + h10 * m * t0z + h01 * p1[2] + h11 * m * t1z;
            if (i > 0) acc[i] = acc[i - 1] + Math.hypot(pts[i][0] - pts[i - 1][0], pts[i][1] - pts[i - 1][1]);
        }
        double total = acc[N];
        int n = (int) Math.max(1, Math.round(total));
        if (n > 200) {
            p.sendActionBar(Component.text("너무 깁니다. (최대 200조각)", NamedTextColor.RED));
            return;
        }
        double[][] q = new double[n + 1][3];
        int k = 0;
        for (int i = 0; i <= n; i++) {
            double target = total * i / n;
            while (k < N - 1 && acc[k + 1] < target) k++;
            double span = acc[k + 1] - acc[k];
            double f = span < 1e-9 ? 0 : (target - acc[k]) / span;
            q[i][0] = pts[k][0] + (pts[k + 1][0] - pts[k][0]) * f;
            q[i][2] = pts[k][1] + (pts[k + 1][1] - pts[k][1]) * f;
            q[i][1] = p0[1] + (p1[1] - p0[1]) * i / n;
        }
        q[0] = new double[]{p0[0], p0[1], p0[2]};
        q[n] = new double[]{p1[0], p1[1], p1[2]};
        double maxStep = 0;
        for (int i = 0; i < n; i++) maxStep = Math.max(maxStep, Math.abs(q[i + 1][1] - q[i][1]));
        if (maxStep > 1.05) {
            p.sendActionBar(Component.text("두 철로의 높이 차가 너무 큽니다. (조각당 1칸 이하여야 함, 필요 조각 " + n + "개)", NamedTextColor.RED));
            return;
        }
        boolean creative = p.getGameMode() == GameMode.CREATIVE;
        if (!creative && countTrack(p) < n) {
            p.sendActionBar(Component.text("철로가 " + n + "개 필요합니다. (가진 개수 " + countTrack(p) + ")", NamedTextColor.RED));
            return;
        }
        for (int i = 0; i < n; i++) {
            double[] mid = {(q[i][0] + q[i + 1][0]) / 2, (q[i][1] + q[i + 1][1]) / 2, (q[i][2] + q[i + 1][2]) / 2};
            if (overlaps(s.world, mid, null)) {
                p.sendActionBar(Component.text("이미 철로가 있는 곳을 지나갑니다. (" + (i + 1) + "번째 조각)", NamedTextColor.RED));
                return;
            }
        }
        String bed = creative ? s.bed : takeTrack(p, n);
        if (bed == null) bed = s.bed;
        Piece prev = null;
        int placed = 0;
        for (int i = 0; i < n; i++) {
            Piece np = makePiece(s.world, q[i], q[i + 1], bed);
            if (i == 0) link(s, bs, np, 0);
            else link(prev, 1, np, 0);
            prev = np;
            placed++;
        }
        link(prev, 1, e, be);
        p.sendActionBar(Component.text("곡선을 연결했습니다. (철로 " + placed + "조각)", NamedTextColor.GREEN));
        s.world.playSound(prev.hit.getLocation(), Sound.BLOCK_GRAVEL_PLACE, 1f, 0.8f);
    }

    void placeCar(Player p, ItemStack item, CarType t) {
        RayTraceResult r = p.rayTraceBlocks(8.0);
        Vector hp = r == null ? null : r.getHitPosition();
        Snap sn = hp == null ? null : nearestPiece(p.getWorld(), hp.getX(), hp.getY() - TILE_TOP, hp.getZ(), 3.0, 2.0);
        if (sn == null) {
            p.sendActionBar(Component.text("철로 위를 바라보고 사용하세요.", NamedTextColor.RED));
            return;
        }
        World w = p.getWorld();
        double[] sp = placeOnTrack(w, sn, p.getLocation().getYaw());
        double x = sp[0], y = sp[1], z = sp[2];
        float yaw = (float) sp[3];
        float pitch = (float) sp[4];

        Car prevCar = null;
        Car nextCar = null;
        double bd = 3.5;
        for (Car o : cars.values()) {
            if (o.world != w || Math.abs(o.y - y) > 8) continue;
            double rad = Math.toRadians(o.yaw);
            double fx = -Math.sin(rad) * SPACING;
            double fz = Math.cos(rad) * SPACING;
            double yd = Math.abs(norm180(yaw - o.yaw));
            if (yd > 70) continue;
            if (o.nextId == null) {
                double d = Math.hypot(x - (o.x - fx), z - (o.z - fz));
                if (d < bd) {
                    bd = d;
                    prevCar = o;
                    nextCar = null;
                }
            }
            boolean oHasPrev = false;
            for (Car q : cars.values()) if (o.id.equals(q.nextId)) oHasPrev = true;
            if (!oHasPrev) {
                double d = Math.hypot(x - (o.x + fx), z - (o.z + fz));
                if (d < bd) {
                    bd = d;
                    nextCar = o;
                    prevCar = null;
                }
            }
        }
        Car other = prevCar != null ? prevCar : nextCar;
        if (other != null) {
            double rad = Math.toRadians(other.yaw);
            double sgn = prevCar != null ? -1 : 1;
            double ex = other.x + sgn * -Math.sin(rad) * SPACING;
            double ez = other.z + sgn * Math.cos(rad) * SPACING;
            Snap s2 = nearestPiece(w, ex, other.y - TILE_TOP, ez, 2.0, 3.0);
            if (s2 != null) {
                double[] sp2 = placeOnTrack(w, s2, other.yaw);
                x = sp2[0];
                y = sp2[1];
                z = sp2[2];
                yaw = (float) sp2[3];
                pitch = (float) sp2[4];
            } else {
                prevCar = null;
                nextCar = null;
            }
        }
        Car car = spawnCar(t, new Location(w, x, y, z), yaw);
        car.pitch = pitch;
        if (prevCar != null) {
            prevCar.nextId = car.id;
            prevCar.saveState();
        }
        if (nextCar != null) car.nextId = nextCar.id;
        car.saveState();
        w.playSound(car.origin(), Sound.BLOCK_ANVIL_PLACE, 1f, 1.2f);
        if (prevCar != null || nextCar != null) p.sendActionBar(Component.text("다른 칸과 연결되었습니다.", NamedTextColor.GREEN));
        if (p.getGameMode() != GameMode.CREATIVE) item.setAmount(item.getAmount() - 1);
    }

    /** 철로 위 지점 -> 칸 위치 {x, y(레일 윗면), z, yaw, pitch} (진행 방향은 hintYaw 와 가까운 쪽) */
    double[] placeOnTrack(World w, Snap s, double hintYaw) {
        Piece pc = s.piece();
        boolean f = Math.abs(norm180(pc.yawFwd() - hintYaw)) <= 90;
        double yw = f ? pc.yawFwd() : pc.yawFwd() + 180;
        double pt = f ? pc.pitchFwd() : -pc.pitchFwd();
        return new double[]{s.pos()[0], s.pos()[1] + TILE_TOP, s.pos()[2], norm180(yw), pt};
    }

    // =====================================================================
    //  클릭 / 회수
    // =====================================================================
    @EventHandler
    public void onClick(PlayerInteractEntityEvent e) {
        Entity en = e.getRightClicked();
        boolean pieceHit = isPieceHit(en);
        if (!pieceHit && !isTrainEntity(en)) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();

        ItemStack held = p.getInventory().getItemInMainHand();
        if (itemKey(held) != null) {
            tryPlace(p, held);
            return;
        }

        if (pieceHit) {
            Piece pc = pieceOf(en);
            if (pc == null) return;
            if (p.isSneaking()) {
                if (pc.maxFork() >= 2) {
                    pc.state = (pc.state + 1) % pc.maxFork();
                    pc.rebuild();
                    p.sendActionBar(Component.text("갈림길 선택: 왼쪽부터 " + (pc.state + 1) + "번째 갈래 (표시: "
                            + (pc.state % 3 == 0 ? "초록" : pc.state % 3 == 1 ? "빨강" : "노랑") + ")", NamedTextColor.GOLD));
                } else {
                    p.sendActionBar(Component.text("갈림길 바로 앞 철로가 아닙니다.", NamedTextColor.GRAY));
                }
            } else {
                selectPiece(p, pc);
            }
            return;
        }

        Car c = carOf(en);
        if (c == null) return;
        String role = role(en);
        int idx = idxOf(en);
        if (role == null) return;

        if (p.isSneaking() && held.getType().isAir()) {
            if (c.nextId == null) {
                p.sendActionBar(Component.text("이 칸 뒤에 연결된 칸이 없습니다.", NamedTextColor.YELLOW));
            } else {
                c.nextId = null;
                c.saveState();
                c.sig = null;
                p.sendActionBar(Component.text("뒤쪽 칸과 분리했습니다.", NamedTextColor.GREEN));
                c.world.playSound(c.origin(), Sound.BLOCK_PISTON_EXTEND, 1f, 1.3f);
            }
            return;
        }
        switch (role) {
            case "fuelhit" -> p.openInventory(c.fuelInv);
            case "cargohit" -> {
                if (idx >= 0 && idx < c.cargo.length) p.openInventory(c.cargo[idx]);
            }
            case "seathit" -> c.mount(p, idx);
            case "hull" -> {
                if (c.type.loco()) c.mount(p, 0);
                else if (c.seats.length > 0) c.mount(p, -1);
            }
            default -> {
            }
        }
    }

    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        Entity en = e.getEntity();
        if (!isTrainEntity(en) && !isPieceHit(en)) return;
        e.setCancelled(true);
        if (e instanceof EntityDamageByEntityEvent d && d.getDamager() instanceof Player p) punched(p, en);
    }

    @EventHandler
    public void onPreAttack(PrePlayerAttackEntityEvent e) {
        if (!isTrainEntity(e.getAttacked()) && !isPieceHit(e.getAttacked())) return;
        e.setCancelled(true);
        punched(e.getPlayer(), e.getAttacked());
    }

    void punched(Player p, Entity en) {
        if (isPieceHit(en)) pickupPiece(p, en);
        else tryPickup(p, en);
    }

    void pickupPiece(Player p, Entity en) {
        Piece t = pieceOf(en);
        if (t == null) return;
        if (!p.isSneaking()) {
            p.sendActionBar(Component.text("웅크린 채로 때리면 철로를 회수합니다.", NamedTextColor.YELLOW));
            return;
        }
        for (int e = 0; e < 2; e++) {
            for (Piece n : neighbors(t, e, null)) {
                n.la.remove(t.id);
                n.lb.remove(t.id);
                n.rebuild();
            }
        }
        t.clearVisual();
        pieces.remove(t.id);
        double[] m = t.at(0.5);
        t.hit.remove();
        if (p.getGameMode() != GameMode.CREATIVE) {
            t.world.dropItemNaturally(new Location(t.world, m[0], m[1] + 0.5, m[2]), trackItem(t.bed, 1));
        }
    }

    void tryPickup(Player p, Entity en) {
        Car c = carOf(en);
        if (c == null) return;
        if (c.hasRider()) {
            if (!c.isRider(p)) p.sendActionBar(Component.text("누군가 타고 있어서 회수할 수 없습니다.", NamedTextColor.RED));
            return;
        }
        if (!p.isSneaking()) {
            p.sendActionBar(Component.text("웅크린 채로 때리면 회수합니다.", NamedTextColor.YELLOW));
            return;
        }
        if (!c.cargoEmpty()) {
            p.sendActionBar(Component.text("화물을 모두 비워야 회수할 수 있습니다.", NamedTextColor.RED));
            return;
        }
        Location l = c.origin().add(0, 1, 0);
        if (c.fuelInv != null) {
            for (ItemStack it : c.fuelInv.getContents()) {
                if (it != null && !it.getType().isAir()) l.getWorld().dropItemNaturally(l, it);
            }
            c.fuelInv.clear();
        }
        detach(c);
        l.getWorld().dropItemNaturally(l, carItem(c.type));
        c.remove();
        cars.remove(c.id);
    }

    void detach(Car c) {
        for (Car o : cars.values()) {
            if (c.id.equals(o.nextId)) {
                o.nextId = null;
                o.saveState();
            }
        }
    }

    // ---- 인벤토리 ----
    void afterCargoChange(Inventory inv) {
        if (inv.getHolder() instanceof CargoHolder h) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                Car c = cars.get(h.carId);
                if (c != null) {
                    c.saveCargo();
                    c.updateCrates();
                }
            });
        } else if (inv.getHolder() instanceof FuelHolder h) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                Car c = cars.get(h.carId);
                if (c != null) c.saveState();
            });
        }
    }

    @EventHandler
    public void onInvClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (top.getHolder() instanceof FuelHolder) handleFuelClick(e, top);
        afterCargoChange(top);
    }

    void handleFuelClick(InventoryClickEvent e, Inventory top) {
        InventoryAction act = e.getAction();
        if (act == InventoryAction.COLLECT_TO_CURSOR || act == InventoryAction.CLONE_STACK) {
            e.setCancelled(true);
            return;
        }
        Inventory clicked = e.getClickedInventory();
        if (clicked == null) return;
        if (clicked.equals(top)) {
            if (e.getRawSlot() != 1) {
                e.setCancelled(true);
                return;
            }
            if (e.getClick() == ClickType.NUMBER_KEY) {
                ItemStack hb = e.getWhoClicked().getInventory().getItem(e.getHotbarButton());
                if (hb != null && !hb.getType().isAir() && !isFuel(hb)) e.setCancelled(true);
                return;
            }
            if (e.getClick() == ClickType.SWAP_OFFHAND) {
                ItemStack off = e.getWhoClicked().getInventory().getItemInOffHand();
                if (!off.getType().isAir() && !isFuel(off)) e.setCancelled(true);
                return;
            }
            ItemStack cur = e.getCursor();
            boolean placing = act == InventoryAction.PLACE_ALL || act == InventoryAction.PLACE_ONE
                    || act == InventoryAction.PLACE_SOME || act == InventoryAction.SWAP_WITH_CURSOR;
            if (placing && !cur.getType().isAir() && !isFuel(cur)) e.setCancelled(true);
        } else if (act == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            e.setCancelled(true);
            ItemStack cur = e.getCurrentItem();
            if (cur == null || !isFuel(cur)) return;
            ItemStack slot = top.getItem(1);
            if (slot == null || slot.getType().isAir()) {
                top.setItem(1, cur.clone());
                e.setCurrentItem(null);
            } else if (slot.isSimilar(cur)) {
                int space = slot.getMaxStackSize() - slot.getAmount();
                int mv = Math.min(space, cur.getAmount());
                if (mv > 0) {
                    slot.setAmount(slot.getAmount() + mv);
                    cur.setAmount(cur.getAmount() - mv);
                    e.setCurrentItem(cur.getAmount() <= 0 ? null : cur);
                }
            }
        }
    }

    @EventHandler
    public void onInvDrag(InventoryDragEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (top.getHolder() instanceof FuelHolder) {
            for (int raw : e.getRawSlots()) {
                if (raw < top.getSize() && (raw != 1 || !isFuel(e.getOldCursor()))) {
                    e.setCancelled(true);
                    return;
                }
            }
        }
        afterCargoChange(top);
    }

    @EventHandler
    public void onInvClose(InventoryCloseEvent e) {
        Inventory inv = e.getInventory();
        if (inv.getHolder() instanceof CargoHolder h) {
            Car c = cars.get(h.carId);
            if (c != null) {
                c.saveCargo();
                c.updateCrates();
            }
        } else if (inv.getHolder() instanceof FuelHolder h) {
            Car c = cars.get(h.carId);
            if (c != null) c.saveState();
        }
    }


    // ---- 청크 로드/언로드 ----
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        for (Entity en : e.getEntities()) {
            if (en instanceof Interaction hb) {
                if (isPieceHit(hb)) {
                    Bukkit.getScheduler().runTask(plugin, () -> tryRegisterPiece(hb));
                } else if ("hull".equals(role(en))) {
                    Bukkit.getScheduler().runTask(plugin, () -> tryRegister(hb, 0));
                }
            }
        }
    }

    @EventHandler
    public void onEntitiesUnload(EntitiesUnloadEvent e) {
        for (Entity en : e.getEntities()) {
            if (!(en instanceof Interaction)) continue;
            if (isPieceHit(en)) {
                Piece pc = pieceOf(en);
                if (pc != null) pieces.remove(pc.id);
            } else if ("hull".equals(role(en))) {
                Car c = carOf(en);
                if (c != null) {
                    c.saveState();
                    cars.remove(c.id);
                }
            }
        }
    }

    // =====================================================================
    //  명령어
    // =====================================================================
    boolean command(CommandSender sender, String[] a) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("인게임에서만 사용할 수 있습니다.");
            return true;
        }
        if (a.length >= 1 && a[0].equalsIgnoreCase("give")) {
            if (a.length >= 2 && a[1].equalsIgnoreCase("track")) {
                String bed = defaultBed;
                if (a.length >= 3) {
                    Material m = Material.matchMaterial(a[2]);
                    if (m == null || !m.isBlock()) {
                        p.sendMessage("블럭 이름이 올바르지 않습니다. 예: /train give track stone_bricks 64");
                        return true;
                    }
                    bed = m.createBlockData().getAsString();
                }
                int n = 64;
                if (a.length >= 4) {
                    try {
                        n = Math.max(1, Math.min(64, Integer.parseInt(a[3])));
                    } catch (NumberFormatException ignored) {
                    }
                }
                p.getInventory().addItem(trackItem(bed, n));
                p.sendMessage("지급: 철로 x" + n);
                return true;
            }
            CarType t = a.length >= 2 ? types.get(a[1].toLowerCase()) : null;
            if (t == null) {
                p.sendMessage("종류: track [블럭] [개수], " + String.join(", ", types.keySet()));
                return true;
            }
            p.getInventory().addItem(carItem(t));
            p.sendMessage("지급: " + t.label());
            return true;
        }
        if (a.length >= 1 && a[0].equalsIgnoreCase("remove")) {
            Car best = null;
            double bd = 8 * 8;
            for (Car c : cars.values()) {
                if (c.world != p.getWorld()) continue;
                double d = c.origin().distanceSquared(p.getLocation());
                if (d < bd) {
                    bd = d;
                    best = c;
                }
            }
            if (best == null) {
                p.sendMessage("8칸 이내에 기차가 없습니다.");
                return true;
            }
            Location l = best.origin().add(0, 1, 0);
            for (Inventory inv : best.cargo) {
                for (ItemStack it : inv.getContents()) if (it != null && !it.getType().isAir()) l.getWorld().dropItemNaturally(l, it);
                inv.clear();
            }
            if (best.fuelInv != null) {
                for (ItemStack it : best.fuelInv.getContents()) if (it != null && !it.getType().isAir()) l.getWorld().dropItemNaturally(l, it);
                best.fuelInv.clear();
            }
            detach(best);
            best.remove();
            cars.remove(best.id);
            p.sendMessage("가장 가까운 기차 칸을 제거했습니다. (내용물은 바닥에 떨어집니다)");
            return true;
        }
        p.sendMessage("/train give track [블럭] [개수]  -  5x1 철로 (바닥 블럭 지정 가능)");
        p.sendMessage("/train give <loco|cargo|pass>  -  조종칸/화물칸/탑승칸");
        p.sendMessage("/train remove  -  가장 가까운 칸 제거(8칸 이내)");
        return true;
    }
}
