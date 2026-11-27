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
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Ageable;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 트랙터.
 * - 앞에 괭이 최대 4개: 괭이 수 = 한 번에 작업하는 줄 수
 * - 수확 모드 : 다 자란 작물을 캔다 (아이템은 바닥에 떨어짐)
 * - 경작/파종 모드 : 경작지면 씨앗을 심고, 흙/잔디면 괭이로 간다
 * - 연료: 정제유 (기본 32L)
 * 좌표계: 트랙터 중심(지면) 기준, +Z = 앞, +X = 왼쪽
 */
public final class TractorManager implements Listener {

    static final int HOE_SLOTS = 4;
    static final int INV_SIZE = 18;
    static final int SEED_START = 9;
    static final double STEP = 1.1;     // 오를 수 있는 높이
    double SEAT_Y = 1.1;   // 좌석 아머스탠드 높이 (모델 배율에 맞춰 계산)
    double SEAT_Z = -0.34;
    double modelScale = 3.0;
    double modelYaw = 180;
    String modelId = "cannon:tractor";
    static final double LANE_Z = -2.1;  // 괭이 위치(뒤쪽)
    double[][] SAMPLES = {
            {0, 0}, {-0.8, -1.2}, {0.8, -1.2}, {-0.8, 0}, {0.8, 0}, {-0.8, 1.3}, {0.8, 1.3}};

    // ---------- 모델 ----------
    static final List<ShipManager.Part> PARTS = buildParts();

    static List<ShipManager.Part> buildParts() {
        List<ShipManager.Part> o = new ArrayList<>();
        String gry = "minecraft:gray_concrete";
        o.add(ShipManager.box(gry, 0f, 0.4f, -1.95f, 4.0f, 0.08f, 0.1f));     // 괭이 걸이(뒤쪽, 작업 폭)
        o.add(ShipManager.box(gry, 0f, 0.4f, -1.775f, 0.12f, 0.08f, 0.35f));  // 걸이 연결대
        return o;
    }

    /** 몸체 아이템 (리소스팩의 cannon:tractor 모델) */
    ItemStack bodyItem() {
        ItemStack it = new ItemStack(Material.PAPER);
        ItemMeta m = it.getItemMeta();
        NamespacedKey k = NamespacedKey.fromString(modelId);
        if (k != null) m.setItemModel(k);
        it.setItemMeta(m);
        return it;
    }

    Transformation bodyTransform() {
        float s = (float) modelScale;
        return new Transformation(new Vector3f(0f, 0.5f * s, -0.1f),
                new Quaternionf().rotationY((float) Math.toRadians(modelYaw)),
                new Vector3f(s, s, s), new Quaternionf());
    }

    // ---------- 필드 ----------
    final CannonPlugin plugin;
    final Map<UUID, Tractor> tractors = new HashMap<>();
    long tickCount = 0;
    boolean internal = false; // 트랙터가 직접 부수는 중(블록 파괴 차단 예외)

    NamespacedKey K_ID, K_ROLE, K_IDX, K_YAW, K_FUEL, K_INV, K_ITEM;
    double maxFuel = 32;
    double maxSpeed = 0.28;
    double accel = 0.02;
    double turn = 3.0;
    double drag = 0.9;
    double fuelTicksPerLiter = 1200;

    TractorManager(CannonPlugin plugin) {
        this.plugin = plugin;
    }

    void enable() {
        K_ID = new NamespacedKey(plugin, "tr_id");
        K_ROLE = new NamespacedKey(plugin, "tr_role");
        K_IDX = new NamespacedKey(plugin, "tr_idx");
        K_YAW = new NamespacedKey(plugin, "tr_yaw");
        K_FUEL = new NamespacedKey(plugin, "tr_fuel");
        K_INV = new NamespacedKey(plugin, "tr_inv");
        K_ITEM = new NamespacedKey(plugin, "tr_item");
        var c = plugin.getConfig();
        maxFuel = c.getDouble("tractor.max-fuel", 32);
        maxSpeed = c.getDouble("tractor.max-speed", 0.28);
        accel = c.getDouble("tractor.accel", 0.02);
        turn = c.getDouble("tractor.turn", 3.0);
        fuelTicksPerLiter = Math.max(1, c.getDouble("tractor.fuel-seconds-per-liter", 60) * 20);
        modelScale = c.getDouble("tractor.model-scale", 3.0);
        modelYaw = c.getDouble("tractor.model-yaw", 180);
        modelId = c.getString("tractor.model", "cannon:tractor");
        double k = modelScale / 3.0;
        SEAT_Y = 5.0 / 16.0 * modelScale + 0.16;
        SEAT_Z = -0.34 * k;
        SAMPLES = new double[][]{{0, 0}, {-0.9 * k, -1.3 * k}, {0.9 * k, -1.3 * k}, {-0.9 * k, 0}, {0.9 * k, 0},
                {-0.9 * k, 1.4 * k}, {0.9 * k, 1.4 * k}};

        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        for (World w : Bukkit.getWorlds()) {
            for (Interaction hb : w.getEntitiesByClass(Interaction.class)) tryRegister(hb, 0);
        }
    }

    void disable() {
        for (Tractor t : tractors.values()) t.saveState();
        tractors.clear();
    }

    // ---------- 유틸 ----------
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

    boolean isTr(Entity en) {
        return en.getPersistentDataContainer().has(K_ID, PersistentDataType.STRING);
    }

    Tractor tractorOf(Entity en) {
        String sid = en.getPersistentDataContainer().get(K_ID, PersistentDataType.STRING);
        if (sid == null) return null;
        try {
            return tractors.get(UUID.fromString(sid));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    Tractor riderTractor(Player p) {
        Entity v = p.getVehicle();
        if (v == null || !isTr(v)) return null;
        Tractor t = tractorOf(v);
        return t != null && p.equals(t.rider()) ? t : null;
    }

    static boolean isHoe(ItemStack it) {
        return it != null && it.getType().name().endsWith("_HOE");
    }

    static Material cropOf(Material seed) {
        return switch (seed) {
            case WHEAT_SEEDS -> Material.WHEAT;
            case BEETROOT_SEEDS -> Material.BEETROOTS;
            case CARROT -> Material.CARROTS;
            case POTATO -> Material.POTATOES;
            case MELON_SEEDS -> Material.MELON_STEM;
            case PUMPKIN_SEEDS -> Material.PUMPKIN_STEM;
            default -> null;
        };
    }

    static boolean isSeed(ItemStack it) {
        return it != null && cropOf(it.getType()) != null;
    }

    /** 자란 정도와 상관없이 작물이면 true */
    static boolean isCrop(Block b) {
        return switch (b.getType()) {
            case WHEAT, CARROTS, POTATOES, BEETROOTS, MELON_STEM, PUMPKIN_STEM, ATTACHED_MELON_STEM,
                 ATTACHED_PUMPKIN_STEM, TORCHFLOWER_CROP, PITCHER_CROP -> true;
            default -> false;
        };
    }

    static double[] off(double lx, double lz, float yaw) {
        double rad = Math.toRadians(yaw);
        double cs = Math.cos(rad);
        double sn = Math.sin(rad);
        return new double[]{lx * cs - lz * sn, lx * sn + lz * cs};
    }

    static ItemStack filler() {
        ItemStack it = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text(" "));
        it.setItemMeta(m);
        return it;
    }

    // ---------- 지형 ----------
    /** (wx,wz) 에서 fromY 근처의 지면 높이. 물/구멍/미로드 청크면 NaN */
    static double surface(World w, double wx, double wz, double fromY) {
        int bx = (int) Math.floor(wx);
        int bz = (int) Math.floor(wz);
        if (!w.isChunkLoaded(bx >> 4, bz >> 4)) return Double.NaN;
        int top = (int) Math.floor(fromY + STEP + 0.01);
        for (int by = top; by >= top - 5; by--) {
            Block b = w.getBlockAt(bx, by, bz);
            if (b.isLiquid()) return Double.NaN;
            if (!b.isPassable()) {
                double mh = 0;
                for (BoundingBox bb : b.getCollisionShape().getBoundingBoxes()) mh = Math.max(mh, bb.getMaxY());
                if (mh <= 0) continue;
                return by + mh;
            }
        }
        return Double.NaN;
    }

    /** 이 위치/방향에 트랙터가 설 수 있으면 지면 높이, 아니면 NaN */
    static double evaluate(World w, double nx, double nz, double ny, float yaw) {
        double best = -1e9;
        for (double[] s : SAMPLES) {
            double[] o = off(s[0], s[1], yaw);
            double sy = surface(w, nx + o[0], nz + o[1], ny);
            if (Double.isNaN(sy)) return Double.NaN;
            best = Math.max(best, sy);
        }
        if (best - ny > STEP) return Double.NaN;
        for (double[] s : SAMPLES) {
            double[] o = off(s[0], s[1], yaw);
            for (double h : new double[]{0.15, 1.0, 1.9}) {
                Block b = w.getBlockAt((int) Math.floor(nx + o[0]), (int) Math.floor(best + h), (int) Math.floor(nz + o[1]));
                if (!b.isPassable() || b.isLiquid()) return Double.NaN;
            }
        }
        return best;
    }

    // ---------- 인벤토리 ----------
    static final class TrHolder implements InventoryHolder {
        final UUID id;
        Inventory inv;

        TrHolder(UUID id) {
            this.id = id;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    Inventory makeInv(UUID id) {
        TrHolder h = new TrHolder(id);
        Inventory inv = Bukkit.createInventory(h, INV_SIZE, Component.text("트랙터  (위: 괭이 4칸 / 아래: 씨앗)"));
        h.inv = inv;
        for (int i = HOE_SLOTS; i < SEED_START; i++) inv.setItem(i, filler());
        return inv;
    }

    // ---------- 아이템 ----------
    ItemStack tractorItem(double fuel, byte[] invData) {
        ItemStack it = new ItemStack(Material.HEART_OF_THE_SEA);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text("트랙터", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(
                Component.text("괭이 최대 4개 · 씨앗 보관함", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("연료: " + (int) Math.ceil(fuel) + "/" + (int) maxFuel + "L", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("땅을 우클릭해서 설치", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)));
        PersistentDataContainer p = m.getPersistentDataContainer();
        p.set(K_ITEM, PersistentDataType.BYTE, (byte) 1);
        p.set(K_FUEL, PersistentDataType.DOUBLE, fuel);
        if (invData != null) p.set(K_INV, PersistentDataType.BYTE_ARRAY, invData);
        NamespacedKey model = NamespacedKey.fromString(modelId);
        if (model != null) m.setItemModel(model);
        m.setMaxStackSize(1);
        it.setItemMeta(m);
        return it;
    }

    boolean isTractorItem(ItemStack it) {
        return it != null && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(K_ITEM, PersistentDataType.BYTE);
    }

    // =====================================================================
    //  트랙터 본체
    // =====================================================================
    final class Tractor {
        final UUID id;
        final Interaction hull;
        final ArmorStand seat;
        final List<BlockDisplay> parts;
        final ItemDisplay[] hoeDisp;
        final Inventory inv;
        final World world;
        double x, y, z;
        float yaw;
        double speed = 0;
        double fuel = 0;
        int mode = 0; // 0 경작, 1 파종, 2 수확
        long lastAct = -100, lastCycle = -100, lastDeny = -100, rightTick = -100;
        boolean prevLeft = false, prevRight = false;
        boolean dirty = false;
        int hoeCount = -1;

        Tractor(UUID id, Interaction hull, ArmorStand seat, List<BlockDisplay> parts, ItemDisplay[] hoeDisp,
                Inventory inv, double x, double y, double z, float yaw) {
            this.id = id;
            this.hull = hull;
            this.seat = seat;
            this.parts = parts;
            this.hoeDisp = hoeDisp;
            this.inv = inv;
            this.world = hull.getWorld();
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
        }

        Player rider() {
            for (Entity e : seat.getPassengers()) if (e instanceof Player p) return p;
            return null;
        }

        Location origin() {
            return new Location(world, x, y, z, yaw, 0f);
        }

        // ---- 저장 ----
        void saveState() {
            PersistentDataContainer p = hull.getPersistentDataContainer();
            p.set(K_YAW, PersistentDataType.FLOAT, yaw);
            p.set(K_FUEL, PersistentDataType.DOUBLE, fuel);
            p.set(K_INV, PersistentDataType.BYTE_ARRAY, invBytes());
        }

        byte[] invBytes() {
            return ItemStack.serializeItemsAsBytes(inv.getContents());
        }

        void loadInv() {
            byte[] data = hull.getPersistentDataContainer().get(K_INV, PersistentDataType.BYTE_ARRAY);
            if (data == null || data.length == 0) return;
            try {
                inv.setContents(ItemStack.deserializeItemsFromBytes(data));
            } catch (Exception ex) {
                plugin.getLogger().warning("트랙터 보관함 복구 실패: " + ex.getMessage());
            }
            fixFillers();
        }

        void fixFillers() {
            for (int i = HOE_SLOTS; i < SEED_START; i++) inv.setItem(i, filler());
        }

        // ---- 괭이 ----
        List<Integer> hoeSlots() {
            List<Integer> l = new ArrayList<>();
            for (int i = 0; i < HOE_SLOTS; i++) if (isHoe(inv.getItem(i))) l.add(i);
            return l;
        }

        /** 괭이 모양(디스플레이) 갱신 */
        void refreshHoes() {
            List<Integer> slots = hoeSlots();
            hoeCount = slots.size();
            int n = slots.size();
            for (int k = 0; k < HOE_SLOTS; k++) {
                ItemDisplay d = hoeDisp[k];
                if (d == null || !d.isValid()) continue;
                if (k < n) {
                    d.setItemStack(inv.getItem(slots.get(k)).clone());
                    float lx = (float) (k - (n - 1) / 2.0);
                    d.setTransformation(new Transformation(new Vector3f(lx, 0.55f, (float) (LANE_Z - 0.05)),
                            new Quaternionf(), new Vector3f(0.9f, 0.9f, 0.9f), new Quaternionf()));
                } else {
                    d.setItemStack(new ItemStack(Material.AIR));
                }
            }
        }

        void damageHoe(int slot) {
            ItemStack it = inv.getItem(slot);
            if (it == null) return;
            ItemMeta meta = it.getItemMeta();
            if (!(meta instanceof Damageable d) || meta.isUnbreakable()) return;
            ItemStack c = it.clone();
            Damageable cd = (Damageable) c.getItemMeta();
            cd.setDamage(cd.getDamage() + 1);
            c.setItemMeta(cd);
            if (cd.getDamage() >= c.getType().getMaxDurability()) {
                inv.setItem(slot, null);
                world.playSound(origin(), Sound.ENTITY_ITEM_BREAK, 1f, 1f);
                refreshHoes();
            } else {
                inv.setItem(slot, c);
            }
            dirty = true;
        }

        int findSeed() {
            for (int i = SEED_START; i < INV_SIZE; i++) if (isSeed(inv.getItem(i))) return i;
            return -1;
        }

        void useSeed(int slot) {
            ItemStack it = inv.getItem(slot);
            if (it == null) return;
            ItemStack c = it.clone();
            c.setAmount(c.getAmount() - 1);
            inv.setItem(slot, c.getAmount() <= 0 ? null : c);
            dirty = true;
        }

        // ---- 연료 ----
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
            world.playSound(origin(), Sound.ITEM_BUCKET_EMPTY, 0.8f, 1f);
            p.sendActionBar(Component.text("정제유 +" + n + "L  (" + (int) Math.ceil(fuel) + "/" + (int) maxFuel + "L)", NamedTextColor.GREEN));
        }

        void mount(Player p) {
            if (p.isInsideVehicle()) return;
            if (rider() != null) {
                p.sendActionBar(Component.text("이미 누군가 타고 있습니다.", NamedTextColor.RED));
                return;
            }
            seat.addPassenger(p);
            p.sendActionBar(Component.text("W/S 전후진 · 시선 방향으로 회전 · A/D 모드 변경 · 좌클릭 작업", NamedTextColor.AQUA));
        }

        // ---- 모드 ----
        static final String[] MODE_NAME = {"경작", "파종", "수확"};

        void cycleMode(Player p, int dir) {
            if (tickCount - lastCycle < 5) return;
            lastCycle = tickCount;
            mode = ((mode + dir) % 3 + 3) % 3;
            world.playSound(origin(), Sound.UI_BUTTON_CLICK, 0.6f, 0.9f + mode * 0.25f);
            status(p);
        }

        void status(Player p) {
            if (p == null) return;
            if (hoeCount < 0) hoeCount = hoeSlots().size();
            int seeds = 0;
            for (int i = SEED_START; i < INV_SIZE; i++) if (isSeed(inv.getItem(i))) seeds += inv.getItem(i).getAmount();
            NamedTextColor mc = mode == 0 ? NamedTextColor.GOLD : mode == 1 ? NamedTextColor.GREEN : NamedTextColor.YELLOW;
            Component c = Component.text("모드: ", NamedTextColor.GRAY)
                    .append(Component.text(MODE_NAME[mode], mc))
                    .append(Component.text("  |  괭이 " + hoeCount + "  씨앗 " + seeds + "  연료 "
                            + (int) Math.ceil(fuel) + "/" + (int) maxFuel + "L",
                            fuel < maxFuel * 0.15 ? NamedTextColor.RED : NamedTextColor.AQUA));
            p.sendActionBar(c);
        }

        // ---- 이동 ----
        void moveAll() {
            Location base = new Location(world, x, y, z, yaw, 0f);
            hull.teleport(new Location(world, x, y, z, yaw, 0f));
            double[] so = off(0, SEAT_Z, yaw);
            seat.teleport(new Location(world, x + so[0], y + SEAT_Y, z + so[1], yaw, 0f),
                    TeleportFlag.EntityState.RETAIN_PASSENGERS);
            for (BlockDisplay d : parts) if (d.isValid()) d.teleport(base);
            for (ItemDisplay d : hoeDisp) if (d != null && d.isValid()) d.teleport(base);
        }

        void remove() {
            for (Entity e : new ArrayList<>(seat.getPassengers())) seat.removePassenger(e);
            seat.remove();
            for (BlockDisplay d : parts) d.remove();
            for (ItemDisplay d : hoeDisp) if (d != null) d.remove();
            hull.remove();
        }

        void tick() {
            Player drv = rider();
            boolean fwd = false, back = false, left = false, right = false;
            if (drv != null) {
                Input in = drv.getCurrentInput();
                fwd = in.isForward();
                back = in.isBackward();
                left = in.isLeft();
                right = in.isRight();
                // A / D = 모드 변경 (누르는 순간 한 번)
                if (left && !prevLeft) cycleMode(drv, -1);
                if (right && !prevRight) cycleMode(drv, 1);
                prevLeft = left;
                prevRight = right;
                left = false;
                right = false;
            } else {
                prevLeft = false;
                prevRight = false;
            }

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
                if (tickCount % 10 == 0 && (fuel > 0 || !engine)) status(drv);
            }

            if (fwd && !back) {
                speed = Math.min(maxSpeed, speed + accel);
            } else if (back && !fwd) {
                speed = speed > 0 ? Math.max(0, speed - accel * 2.5) : Math.max(-maxSpeed * 0.4, speed - accel);
            } else {
                speed *= (drv == null ? 0.8 : drag);
            }
            if (Math.abs(speed) < 0.004) speed = 0;

            // 방향: 운전자의 시선(마우스) 방향으로 천천히 돌아감
            float dYaw = 0f;
            if (drv != null) {
                float diff = drv.getLocation().getYaw() - yaw;
                while (diff > 180f) diff -= 360f;
                while (diff < -180f) diff += 360f;
                if (Math.abs(diff) > 2f) dYaw = (float) Math.max(-turn, Math.min(turn, diff));
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
            if (dYaw != 0f && Double.isNaN(evaluate(world, x, z, y, ny))) ny = yaw;

            double rad = Math.toRadians(ny);
            double dx = -Math.sin(rad);
            double dz = Math.cos(rad);
            boolean blocked = false;
            if (speed != 0) {
                int steps = (int) Math.max(1, Math.ceil(Math.abs(speed) / 0.25));
                double sx = dx * speed / steps;
                double sz = dz * speed / steps;
                for (int i = 0; i < steps; i++) {
                    double nx = x + sx;
                    double nz = z + sz;
                    if (Double.isNaN(evaluate(world, nx, nz, y, ny))) {
                        blocked = true;
                        break;
                    }
                    x = nx;
                    z = nz;
                }
            }
            yaw = ny;
            double t = evaluate(world, x, z, y, yaw);
            if (!Double.isNaN(t)) {
                if (t > y) y = Math.min(t, y + 0.3);
                else y = Math.max(t, y - 0.4);
            }
            if (blocked) speed *= 0.3;

            moveAll();
            dirty = true;

            // 경작 모드: 클릭 없이 지나가는 자리를 자동으로 갈아 줌
            if (drv != null && mode == 0 && speed > 0.01 && tickCount % 2 == 0) act(drv, true);

        }

        // ---- 작업 (클릭할 때마다 앞쪽 괭이 줄 칸들에 현재 모드 작업을 1회) ----
        void act(Player drv) {
            act(drv, false);
        }

        void act(Player drv, boolean auto) {
            if (!auto) {
                if (tickCount - lastAct < 4) return;
                lastAct = tickCount;
            }
            List<Integer> slots = hoeSlots();
            int n = slots.size();
            if (n == 0) {
                if (!auto || tickCount % 60 == 0)
                    drv.sendActionBar(Component.text("괭이가 없습니다. (웅크리고 우클릭 → 보관함)", NamedTextColor.RED));
                return;
            }
            boolean denied = false;
            for (int k = 0; k < n; k++) {
                double lx = k - (n - 1) / 2.0;
                double[] o = off(lx, LANE_Z, yaw);
                double wx = x + o[0];
                double wz = z + o[1];
                int bx = (int) Math.floor(wx);
                int bz = (int) Math.floor(wz);
                if (!world.isChunkLoaded(bx >> 4, bz >> 4)) continue;
                Block g = null;
                int top = (int) Math.floor(y + 1.5);
                for (int by = top; by >= top - 4; by--) {
                    Block b = world.getBlockAt(bx, by, bz);
                    if (b.isLiquid()) break;
                    if (!b.isPassable()) {
                        g = b;
                        break;
                    }
                }
                if (g == null) continue;
                int hoeSlot = slots.get(k);
                ItemStack hoe = inv.getItem(hoeSlot);
                if (hoe == null) continue;

                if (mode == 2) {
                    Block target = null;
                    if (g.getType() == Material.MELON || g.getType() == Material.PUMPKIN) target = g;
                    else if (isCrop(g.getRelative(BlockFace.UP))) target = g.getRelative(BlockFace.UP);
                    if (target != null && !breakCrop(drv, target, hoe)) denied = true;
                } else if (mode == 1) {
                    Block above = g.getRelative(BlockFace.UP);
                    if (g.getType() == Material.FARMLAND && above.getType().isAir()) {
                        int sSlot = findSeed();
                        if (sSlot >= 0 && !plantSeed(drv, g, above, sSlot)) denied = true;
                    }
                } else {
                    Block above = g.getRelative(BlockFace.UP);
                    if (above.getType().isAir() && (g.getType() == Material.DIRT
                            || g.getType() == Material.GRASS_BLOCK || g.getType() == Material.DIRT_PATH)) {
                        if (!till(drv, g, hoe, hoeSlot)) denied = true;
                    }
                }
            }
            if (denied && tickCount - lastDeny > 40) {
                lastDeny = tickCount;
                drv.sendActionBar(Component.text("이 땅에서는 작업할 권한이 없습니다.", NamedTextColor.RED));
            }
        }

        boolean breakCrop(Player drv, Block c, ItemStack hoe) {
            BlockBreakEvent ev = new BlockBreakEvent(c, drv);
            internal = true;
            try {
                Bukkit.getPluginManager().callEvent(ev);
            } finally {
                internal = false;
            }
            if (ev.isCancelled()) return false;
            c.breakNaturally(hoe);
            return true;
        }

        boolean plantSeed(Player drv, Block g, Block above, int sSlot) {
            ItemStack seed = inv.getItem(sSlot);
            if (seed == null) return true;
            Material crop = cropOf(seed.getType());
            if (crop == null) return true;
            BlockState replaced = above.getState();
            above.setType(crop);
            BlockPlaceEvent ev = new BlockPlaceEvent(above, replaced, g, seed.clone(), drv, true, EquipmentSlot.HAND);
            Bukkit.getPluginManager().callEvent(ev);
            if (ev.isCancelled() || !ev.canBuild()) {
                replaced.update(true, false);
                return false;
            }
            useSeed(sSlot);
            world.playSound(above.getLocation(), Sound.ITEM_CROP_PLANT, 0.8f, 1f);
            return true;
        }

        boolean till(Player drv, Block g, ItemStack hoe, int hoeSlot) {
            BlockState replaced = g.getState();
            g.setType(Material.FARMLAND);
            BlockPlaceEvent ev = new BlockPlaceEvent(g, replaced, g.getRelative(BlockFace.DOWN), hoe.clone(), drv, true, EquipmentSlot.HAND);
            Bukkit.getPluginManager().callEvent(ev);
            if (ev.isCancelled() || !ev.canBuild()) {
                replaced.update(true, false);
                return false;
            }
            world.playSound(g.getLocation(), Sound.ITEM_HOE_TILL, 0.8f, 1f);
            damageHoe(hoeSlot);
            return true;
        }
    }

    // =====================================================================
    //  생성 / 복구
    // =====================================================================
    Tractor spawn(Location at, float yaw, double fuel, byte[] invData) {
        UUID id = UUID.randomUUID();
        String sid = id.toString();
        World w = at.getWorld();
        double x = at.getX(), y = at.getY(), z = at.getZ();
        Location base = new Location(w, x, y, z, yaw, 0f);

        Interaction hull = w.spawn(base, Interaction.class, e -> {
            e.setInteractionWidth((float) (2.2 * modelScale / 3.0));
            e.setInteractionHeight((float) (1.9 * modelScale / 3.0));
            e.setResponsive(true);
            tag(e, sid, "hull", 0);
            e.getPersistentDataContainer().set(K_YAW, PersistentDataType.FLOAT, yaw);
        });
        double[] so = off(0, SEAT_Z, yaw);
        ArmorStand seat = w.spawn(new Location(w, x + so[0], y + SEAT_Y, z + so[1], yaw, 0f), ArmorStand.class, a -> {
            a.setInvisible(true);
            a.setMarker(true);
            a.setSmall(true);
            a.setGravity(false);
            a.setInvulnerable(true);
            a.setSilent(true);
            a.setPersistent(true);
            tag(a, sid, "seat", 0);
        });
        List<BlockDisplay> parts = new ArrayList<>();
        for (int i = 0; i < PARTS.size(); i++) {
            final ShipManager.Part pt = PARTS.get(i);
            final int idx = i;
            parts.add(w.spawn(base, BlockDisplay.class, d -> {
                d.setBlock(Bukkit.createBlockData(pt.data()));
                d.setTransformation(ShipManager.transformOf(pt));
                d.setTeleportDuration(3);
                d.setViewRange(1.5f);
                tag(d, sid, "part", idx);
            }));
        }
        ItemDisplay[] hoeDisp = new ItemDisplay[HOE_SLOTS + 1];   // 마지막 칸 = 트랙터 몸체
        for (int i = 0; i <= HOE_SLOTS; i++) {
            final int idx = i;
            hoeDisp[i] = w.spawn(base, ItemDisplay.class, d -> {
                if (idx == HOE_SLOTS) {
                    d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                    d.setItemStack(bodyItem());
                    d.setTransformation(bodyTransform());
                    d.setViewRange(2.5f);
                } else {
                    d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                    d.setViewRange(1.5f);
                }
                d.setTeleportDuration(3);
                tag(d, sid, "hoe", idx);
            });
        }
        Tractor t = new Tractor(id, hull, seat, parts, hoeDisp, makeInv(id), x, y, z, yaw);
        t.fuel = Math.min(fuel, maxFuel);
        if (invData != null) {
            try {
                t.inv.setContents(ItemStack.deserializeItemsFromBytes(invData));
                t.fixFillers();
            } catch (Exception ex) {
                plugin.getLogger().warning("트랙터 보관함 복구 실패: " + ex.getMessage());
            }
        }
        tractors.put(id, t);
        t.refreshHoes();
        t.saveState();
        return t;
    }

    void tryRegister(Interaction hb, int attempt) {
        if (!hb.isValid()) return;
        PersistentDataContainer hp = hb.getPersistentDataContainer();
        if (!"hull".equals(hp.get(K_ROLE, PersistentDataType.STRING))) return;
        String sid = hp.get(K_ID, PersistentDataType.STRING);
        if (sid == null) return;
        UUID id;
        try {
            id = UUID.fromString(sid);
        } catch (IllegalArgumentException ex) {
            return;
        }
        if (tractors.containsKey(id)) return;

        ArmorStand seat = null;
        Interaction rearFound = null;
        List<BlockDisplay> parts = new ArrayList<>();
        ItemDisplay[] hoeDisp = new ItemDisplay[HOE_SLOTS + 1];
        for (Entity en : hb.getNearbyEntities(8, 6, 8)) {
            PersistentDataContainer p = en.getPersistentDataContainer();
            if (!sid.equals(p.get(K_ID, PersistentDataType.STRING))) continue;
            String role = p.get(K_ROLE, PersistentDataType.STRING);
            if (role == null) continue;
            switch (role) {
                case "seat" -> {
                    if (en instanceof ArmorStand a) seat = a;
                }
                case "part" -> {
                    if (en instanceof BlockDisplay d) parts.add(d);
                }
                case "rear" -> {
                    if (en instanceof Interaction r) rearFound = r;
                }
                case "hoe" -> {
                    int idx = idxOf(en);
                    if (en instanceof ItemDisplay d && idx >= 0 && idx <= HOE_SLOTS) hoeDisp[idx] = d;
                }
                default -> {
                }
            }
        }
        boolean complete = seat != null && parts.size() == PARTS.size();
        for (ItemDisplay d : hoeDisp) if (d == null) complete = false;
        if (!complete) {
            if (attempt < 5) {
                final int next = attempt + 1;
                Bukkit.getScheduler().runTaskLater(plugin, () -> tryRegister(hb, next), 20L);
            }
            return;
        }
        Float sy = hp.get(K_YAW, PersistentDataType.FLOAT);
        float yaw = sy == null ? hb.getLocation().getYaw() : sy;
        Tractor t = new Tractor(id, hb, seat, parts, hoeDisp, makeInv(id), hb.getX(), hb.getY(), hb.getZ(), yaw);
        if (rearFound != null) rearFound.remove(); // 이전 버전의 뒤쪽 클릭 범위 정리
        t.loadInv();
        Double fv = hp.get(K_FUEL, PersistentDataType.DOUBLE);
        t.fuel = fv == null ? 0 : Math.min(fv, maxFuel);
        tractors.put(id, t);
        t.refreshHoes();
        t.moveAll();
    }

    // =====================================================================
    //  틱
    // =====================================================================
    void tick() {
        tickCount++;
        for (Tractor t : new ArrayList<>(tractors.values())) {
            if (!t.hull.isValid()) {
                tractors.remove(t.id);
                continue;
            }
            t.tick();
        }
    }

    // =====================================================================
    //  이벤트
    // =====================================================================
    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        Action a = e.getAction();
        Tractor ride = riderTractor(p);
        if (ride != null) {
            e.setCancelled(true);
            if (a == Action.RIGHT_CLICK_AIR || a == Action.RIGHT_CLICK_BLOCK) ride.rightTick = tickCount;
            return;
        }
        if (a != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = e.getItem();
        if (!isTractorItem(item)) return;
        e.setCancelled(true);
        if (e.getBlockFace() != BlockFace.UP || e.getClickedBlock() == null) {
            p.sendActionBar(Component.text("땅의 윗면을 우클릭해서 설치하세요.", NamedTextColor.RED));
            return;
        }
        Block cb = e.getClickedBlock();
        double top = 0;
        for (BoundingBox bb : cb.getCollisionShape().getBoundingBoxes()) top = Math.max(top, bb.getMaxY());
        if (top <= 0) top = 1;
        double ax = e.getInteractionPoint() != null ? e.getInteractionPoint().getX() : cb.getX() + 0.5;
        double az = e.getInteractionPoint() != null ? e.getInteractionPoint().getZ() : cb.getZ() + 0.5;
        float yaw = p.getLocation().getYaw();
        double ay = cb.getY() + top;
        double g = evaluate(cb.getWorld(), ax, az, ay, yaw);
        if (Double.isNaN(g) || Math.abs(g - ay) > 0.3) {
            p.sendActionBar(Component.text("여기엔 놓을 수 없습니다. (평평하고 넓은 빈 땅이 필요합니다)", NamedTextColor.RED));
            return;
        }
        ItemMeta m = item.getItemMeta();
        Double fuel = m.getPersistentDataContainer().get(K_FUEL, PersistentDataType.DOUBLE);
        byte[] data = m.getPersistentDataContainer().get(K_INV, PersistentDataType.BYTE_ARRAY);
        spawn(new Location(cb.getWorld(), ax, g, az), yaw, fuel == null ? 0 : fuel, data);
        cb.getWorld().playSound(new Location(cb.getWorld(), ax, g, az), Sound.BLOCK_ANVIL_PLACE, 0.6f, 1.2f);
        if (p.getGameMode() != GameMode.CREATIVE) item.setAmount(item.getAmount() - 1);
    }

    @EventHandler
    public void onSwing(PlayerAnimationEvent e) {
        if (e.getAnimationType() != PlayerAnimationType.ARM_SWING) return;
        Player p = e.getPlayer();
        Tractor t = riderTractor(p);
        if (t == null) return;
        // 우클릭에 딸려오는 팔 흔들기는 무시하고 좌클릭만 작업으로 인정
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (tickCount - t.rightTick > 1 && p.equals(t.rider())) t.act(p);
        });
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent e) {
        if (internal) return;
        if (riderTractor(e.getPlayer()) != null) e.setCancelled(true);
    }

    @EventHandler
    public void onBlockDamage(BlockDamageEvent e) {
        if (riderTractor(e.getPlayer()) != null) e.setCancelled(true);
    }

    @EventHandler
    public void onClick(PlayerInteractEntityEvent e) {
        Entity en = e.getRightClicked();
        if (!isTr(en)) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        Tractor t = tractorOf(en);
        if (t == null) return;
        Player p = e.getPlayer();
        if (p.equals(t.rider())) {
            t.rightTick = tickCount;
            return;
        }
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (plugin.is(hand, plugin.oilItem)) {
            t.addFuel(p, hand);
            return;
        }
        if (p.isSneaking()) {
            if (t.rider() != null) {
                p.sendActionBar(Component.text("누군가 타고 있습니다.", NamedTextColor.RED));
                return;
            }
            p.openInventory(t.inv);
            return;
        }
        t.mount(p);
    }

    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        Entity en = e.getEntity();
        if (!isTr(en)) return;
        e.setCancelled(true);
        if (e instanceof EntityDamageByEntityEvent d && d.getDamager() instanceof Player p) tryPickup(p, en);
    }

    @EventHandler
    public void onPreAttack(PrePlayerAttackEntityEvent e) {
        if (!isTr(e.getAttacked())) return;
        e.setCancelled(true);
        tryPickup(e.getPlayer(), e.getAttacked());
    }

    void tryPickup(Player p, Entity en) {
        Tractor t = tractorOf(en);
        if (t == null) return;
        if (t.rider() != null) {
            if (p.equals(t.rider())) t.act(p);
            else p.sendActionBar(Component.text("누군가 타고 있어서 회수할 수 없습니다.", NamedTextColor.RED));
            return;
        }
        if (!p.isSneaking()) {
            p.sendActionBar(Component.text("웅크린 채로 때리면 트랙터를 회수합니다. (괭이·씨앗·연료는 아이템에 보존)", NamedTextColor.YELLOW));
            return;
        }
        for (var v : new ArrayList<>(t.inv.getViewers())) v.closeInventory();
        Location l = t.origin().add(0, 0.5, 0);
        l.getWorld().dropItemNaturally(l, tractorItem(t.fuel, t.invBytes()));
        t.remove();
        tractors.remove(t.id);
    }

    // ---- 보관함 ----
    @EventHandler
    public void onInvClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof TrHolder h)) return;
        int raw = e.getRawSlot();
        if (raw >= HOE_SLOTS && raw < SEED_START) {
            e.setCancelled(true);
            return;
        }
        afterChange(h, e.getWhoClicked() instanceof Player p ? p : null);
    }

    @EventHandler
    public void onInvDrag(InventoryDragEvent e) {
        if (!(e.getInventory().getHolder() instanceof TrHolder h)) return;
        for (int raw : e.getRawSlots()) {
            if (raw >= HOE_SLOTS && raw < SEED_START) {
                e.setCancelled(true);
                return;
            }
        }
        afterChange(h, e.getWhoClicked() instanceof Player p ? p : null);
    }

    @EventHandler
    public void onInvClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof TrHolder h)) return;
        Tractor t = tractors.get(h.id);
        if (t == null) return;
        sanitize(t, e.getPlayer() instanceof Player p ? p : null);
        t.refreshHoes();
        t.saveState();
    }

    void afterChange(TrHolder h, Player p) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Tractor t = tractors.get(h.id);
            if (t == null) return;
            sanitize(t, p);
            t.refreshHoes();
            t.saveState();
        });
    }

    /** 괭이 칸엔 괭이만, 씨앗 칸엔 씨앗만. 잘못 들어간 건 플레이어에게 돌려줌 */
    void sanitize(Tractor t, Player p) {
        for (int i = 0; i < INV_SIZE; i++) {
            if (i >= HOE_SLOTS && i < SEED_START) continue;
            ItemStack it = t.inv.getItem(i);
            if (it == null || it.getType().isAir()) continue;
            boolean ok = i < HOE_SLOTS ? isHoe(it) : isSeed(it);
            if (ok) continue;
            t.inv.setItem(i, null);
            if (p != null) {
                for (ItemStack left : p.getInventory().addItem(it).values())
                    p.getWorld().dropItemNaturally(p.getLocation(), left);
            } else {
                t.world.dropItemNaturally(t.origin(), it);
            }
        }
        t.fixFillers();
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
                Tractor t = tractorOf(en);
                if (t != null) {
                    t.saveState();
                    tractors.remove(t.id);
                }
            }
        }
    }

    // =====================================================================
    //  명령어  /tractor give  /  /tractor remove
    // =====================================================================
    boolean command(CommandSender sender, String[] a) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("인게임에서만 사용할 수 있습니다.");
            return true;
        }
        if (a.length >= 1 && a[0].equalsIgnoreCase("give")) {
            p.getInventory().addItem(tractorItem(0, null));
            p.sendMessage("트랙터를 지급했습니다. (연료 0L - 정제유를 우클릭으로 넣으세요)");
            return true;
        }
        if (a.length >= 1 && a[0].equalsIgnoreCase("remove")) {
            Tractor best = null;
            double bd = 8 * 8;
            for (Tractor t : tractors.values()) {
                if (t.world != p.getWorld()) continue;
                double d = t.origin().distanceSquared(p.getLocation());
                if (d < bd) {
                    bd = d;
                    best = t;
                }
            }
            if (best == null) {
                p.sendMessage("8칸 안에 트랙터가 없습니다.");
            } else {
                for (var v : new ArrayList<>(best.inv.getViewers())) v.closeInventory();
                best.remove();
                tractors.remove(best.id);
                p.sendMessage("트랙터를 삭제했습니다.");
            }
            return true;
        }
        p.sendMessage("/tractor give  - 트랙터 지급");
        p.sendMessage("/tractor remove - 가까운 트랙터 삭제");
        return true;
    }
}
