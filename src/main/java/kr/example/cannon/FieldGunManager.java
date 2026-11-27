package kr.example.cannon;

import io.papermc.paper.entity.TeleportFlag;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 야포 (1차 세계대전기 야전포 느낌).
 * - 긴 포신, 포방패, 큰 바퀴, 갈라진 포미(트레일) 다리, 발사 시 포신 후퇴(주퇴)
 * - 대포보다 사거리·폭발이 크고 장전이 빠름. 탄은 대포알이 아닌 "야포탄"
 * 좌표계: 야포 중심(지면) 기준, +Z = 포구 방향, +X = 왼쪽, +Y = 위
 */
public final class FieldGunManager implements Listener {

    /** group 0 = 포가(고정), 1 = 포신 고정부(방열통), 2 = 포신 주퇴부(관/포미/포구) */
    record GP(ShipManager.Part p, int group) {}

    /** 포신 회전 중심 */
    static final Vector3f PIVOT = new Vector3f(0f, 0.95f, 0.2f);
    static final double SEAT_Z = -0.7;
    static final double SEAT_Y = 0.93;
    static final float PITCH_MIN = -50f; // 위
    static final float PITCH_MAX = 8f;   // 아래
    static final float MUZZLE = 2.45f;   // 피벗에서 포구까지

    static final List<GP> GPS = build();

    static List<GP> build() {
        List<GP> o = new ArrayList<>();
        String olive = "minecraft:green_terracotta";
        String gray = "minecraft:gray_concrete";
        String iron = "minecraft:iron_block";
        String blk = "minecraft:black_concrete";
        String wood = "minecraft:dark_oak_planks";
        String spoke = "minecraft:spruce_planks";

        // ---- 바퀴 (살 4쌍 + 테 8조각 + 허브) ----
        float wy = 0.62f;
        for (int sx = -1; sx <= 1; sx += 2) {
            float wx = sx * 1.0f;
            o.add(new GP(ShipManager.box(iron, wx, wy, 0f, 0.22f, 0.26f, 0.26f), 0));
            for (int a = 0; a < 180; a += 45) {
                o.add(new GP(ShipManager.tilt(spoke, wx, wy, 0f, 0.1f, 0.1f, 1.0f, a), 0));
            }
            for (int k = 0; k < 8; k++) {
                double phi = Math.toRadians(45.0 * k);
                float cy = (float) (wy + 0.55 * Math.cos(phi));
                float cz = (float) (0.55 * Math.sin(phi));
                o.add(new GP(ShipManager.tilt(wood, wx, cy, cz, 0.16f, 0.12f, 0.44f, 45f * k), 0));
            }
        }
        o.add(new GP(ShipManager.box(gray, 0f, wy, 0f, 2.0f, 0.1f, 0.1f), 0));           // 차축
        o.add(new GP(ShipManager.box(olive, 0f, 0.72f, 0.05f, 0.7f, 0.26f, 0.9f), 0));    // 포가 몸체
        // ---- 갈라진 포미 다리(스플릿 트레일) ----
        for (int sx = -1; sx <= 1; sx += 2) {
            o.add(new GP(ShipManager.wall(olive, sx * 0.2f, -0.3f, sx * 0.95f, -2.6f, 0.17f, 0.16f, 0.2f), 0));
            o.add(new GP(ShipManager.box(gray, sx * 0.95f, 0.08f, -2.6f, 0.3f, 0.16f, 0.3f), 0)); // 지지판
        }
        o.add(new GP(ShipManager.box(olive, 0f, 0.3f, -0.5f, 0.5f, 0.12f, 0.4f), 0));      // 다리 연결부
        // ---- 포방패 (포신이 지나가는 구멍을 남김) ----
        o.add(new GP(ShipManager.box(olive, -0.525f, 1.05f, 0.55f, 0.65f, 1.1f, 0.07f), 0));
        o.add(new GP(ShipManager.box(olive, 0.525f, 1.05f, 0.55f, 0.65f, 1.1f, 0.07f), 0));
        o.add(new GP(ShipManager.box(olive, 0f, 1.45f, 0.55f, 0.4f, 0.3f, 0.07f), 0));
        o.add(new GP(ShipManager.box(olive, 0f, 0.64f, 0.55f, 0.4f, 0.28f, 0.07f), 0));
        o.add(new GP(ShipManager.box(gray, -0.525f, 1.6f, 0.55f, 0.7f, 0.06f, 0.1f), 0));  // 방패 윗테
        o.add(new GP(ShipManager.box(gray, 0.525f, 1.6f, 0.55f, 0.7f, 0.06f, 0.1f), 0));
        // ---- 사수 의자 ----
        o.add(new GP(ShipManager.box(gray, 0f, 0.35f, (float) SEAT_Z, 0.1f, 0.7f, 0.1f), 0));
        o.add(new GP(ShipManager.box(blk, 0f, 0.72f, (float) SEAT_Z, 0.42f, 0.1f, 0.42f), 0));
        o.add(new GP(ShipManager.box(blk, 0f, 0.95f, (float) SEAT_Z - 0.22f, 0.42f, 0.4f, 0.08f), 0));

        // ---- 포신 (피벗 기준 좌표, +Z 가 앞) ----
        o.add(new GP(ShipManager.box(gray, 0f, 0f, 0.45f, 0.36f, 0.36f, 0.9f), 1));       // 방열통(고정)
        o.add(new GP(ShipManager.box(blk, 0f, 0f, 1.0f, 0.22f, 0.22f, 2.6f), 2));         // 관
        o.add(new GP(ShipManager.box(iron, 0f, 0f, -0.3f, 0.38f, 0.38f, 0.4f), 2));       // 포미
        o.add(new GP(ShipManager.box(iron, 0f, 0f, 2.25f, 0.3f, 0.3f, 0.14f), 2));        // 포구 제퇴기
        o.add(new GP(ShipManager.box(iron, 0f, 0f, 0.95f, 0.28f, 0.28f, 0.1f), 2));       // 보강대
        return o;
    }

    // ---------- 설정 / 키 ----------
    final CannonPlugin plugin;
    final Map<UUID, Gun> guns = new HashMap<>();
    final class Shot {
        final ItemDisplay vis;
        Location last;

        Shot(ItemDisplay vis, Location last) {
            this.vis = vis;
            this.last = last;
        }
    }

    final Map<Snowball, Shot> shells = new HashMap<>();
    NamespacedKey K_ID, K_PART, K_YAW, K_PITCH, K_LOADED, K_ITEM_GUN, K_ITEM_SHELL, K_PROJ;
    NamespacedKey shellModel = null;
    float yawSpeed = 2.5f, pitchSpeed = 1.5f, speed = 2.6f, power = 5.0f;
    double blastScale = 1.5;
    int loadTicks = 60;
    long tickCount = 0;

    FieldGunManager(CannonPlugin plugin) {
        this.plugin = plugin;
    }

    void enable() {
        K_ID = new NamespacedKey(plugin, "fg_id");
        K_PART = new NamespacedKey(plugin, "fg_part");
        K_YAW = new NamespacedKey(plugin, "fg_yaw");
        K_PITCH = new NamespacedKey(plugin, "fg_pitch");
        K_LOADED = new NamespacedKey(plugin, "fg_loaded");
        K_ITEM_GUN = new NamespacedKey(plugin, "fg_item_gun");
        K_ITEM_SHELL = new NamespacedKey(plugin, "fg_item_shell");
        K_PROJ = new NamespacedKey(plugin, "fg_proj");
        var c = plugin.getConfig();
        yawSpeed = (float) c.getDouble("fieldgun.turn-speed-yaw", 2.5);
        pitchSpeed = (float) c.getDouble("fieldgun.turn-speed-pitch", 1.5);
        speed = (float) c.getDouble("fieldgun.projectile-speed", 2.6);
        power = (float) c.getDouble("fieldgun.explosion-power", 5.0);
        blastScale = Math.max(1.0, c.getDouble("fieldgun.blast-radius-scale", 1.5));
        loadTicks = Math.max(5, (int) (c.getDouble("fieldgun.load-seconds", 3) * 20));
        String sm = c.getString("fieldgun.shell-model", "");
        shellModel = (sm == null || sm.isBlank()) ? null : NamespacedKey.fromString(sm.trim());

        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        for (World w : Bukkit.getWorlds()) {
            for (Interaction hb : w.getEntitiesByClass(Interaction.class)) tryRegister(hb, 0);
        }
    }

    void disable() {
        for (Gun g : guns.values()) g.save();
        guns.clear();
        for (Map.Entry<Snowball, Shot> en : shells.entrySet()) {
            en.getKey().remove();
            en.getValue().vis.remove();
        }
        shells.clear();
    }

    // ---------- 아이템 ----------
    ItemStack gunItem() {
        ItemStack it = new ItemStack(Material.SPYGLASS);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text("야포", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(
                Component.text("대포보다 멀리, 강하게 쏘는 야전포", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("땅을 우클릭해서 설치", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)));
        m.getPersistentDataContainer().set(K_ITEM_GUN, PersistentDataType.BYTE, (byte) 1);
        m.setMaxStackSize(1);
        it.setItemMeta(m);
        return it;
    }

    ItemStack shellItem(int amount) {
        ItemStack it = new ItemStack(Material.COPPER_INGOT, amount);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text("야포탄", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(Component.text("야포 전용 포탄", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        m.getPersistentDataContainer().set(K_ITEM_SHELL, PersistentDataType.BYTE, (byte) 1);
        if (shellModel != null) m.setItemModel(shellModel);
        it.setItemMeta(m);
        return it;
    }

    boolean isMark(ItemStack it, NamespacedKey key) {
        if (it == null || !it.hasItemMeta()) return false;
        return it.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    // ---------- 유틸 ----------
    static double[] off(double lx, double lz, float yaw) {
        double rad = Math.toRadians(yaw);
        double cs = Math.cos(rad);
        double sn = Math.sin(rad);
        return new double[]{lx * cs - lz * sn, lx * sn + lz * cs};
    }

    static float wrap(float a) {
        a %= 360f;
        if (a >= 180f) a -= 360f;
        if (a < -180f) a += 360f;
        return a;
    }

    static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    boolean isGunEntity(Entity en) {
        return en.getPersistentDataContainer().has(K_ID, PersistentDataType.STRING);
    }

    Gun gunOfEntity(Entity en) {
        String s = en.getPersistentDataContainer().get(K_ID, PersistentDataType.STRING);
        if (s == null) return null;
        try {
            return guns.get(UUID.fromString(s));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    Gun gunOf(Player p) {
        Entity v = p.getVehicle();
        if (v == null || !isGunEntity(v)) return null;
        Gun g = gunOfEntity(v);
        return g != null && p.equals(g.rider()) ? g : null;
    }

    // =====================================================================
    //  야포 객체
    // =====================================================================
    final class Gun {
        final UUID id;
        final Interaction hitbox;
        final ArmorStand seat;
        final List<BlockDisplay> parts;
        float yaw, pitch;
        boolean loaded;
        int loading = -1;
        float recoil = 0f;
        boolean dirty = false;
        long lastFire = -100;

        Gun(UUID id, Interaction hb, ArmorStand seat, List<BlockDisplay> parts, float yaw, float pitch, boolean loaded) {
            this.id = id;
            this.hitbox = hb;
            this.seat = seat;
            this.parts = parts;
            this.yaw = yaw;
            this.pitch = pitch;
            this.loaded = loaded;
        }

        Location origin() {
            return hitbox.getLocation();
        }

        Player rider() {
            for (Entity en : seat.getPassengers()) if (en instanceof Player p) return p;
            return null;
        }

        void save() {
            PersistentDataContainer p = hitbox.getPersistentDataContainer();
            p.set(K_LOADED, PersistentDataType.BYTE, (byte) (loaded ? 1 : 0));
            p.set(K_YAW, PersistentDataType.FLOAT, yaw);
            p.set(K_PITCH, PersistentDataType.FLOAT, pitch);
            dirty = false;
        }

        void remove() {
            for (Entity en : new ArrayList<>(seat.getPassengers())) seat.removePassenger(en);
            seat.remove();
            for (BlockDisplay d : parts) d.remove();
            hitbox.remove();
        }

        /** 포가(고정부) 변환: 한 번만 설정 */
        void applyStatic() {
            for (int i = 0; i < GPS.size(); i++) {
                GP gp = GPS.get(i);
                if (gp.group() != 0) continue;
                parts.get(i).setTransformation(ShipManager.transformOf(gp.p()));
            }
        }

        /** 포신 변환: 고각(pitch) + 주퇴(recoil) 반영 */
        void applyBarrel(boolean instant) {
            Quaternionf q = new Quaternionf().rotationX((float) Math.toRadians(pitch));
            for (int i = 0; i < GPS.size(); i++) {
                GP gp = GPS.get(i);
                if (gp.group() == 0) continue;
                ShipManager.Part p = gp.p();
                float cz = p.cz() - (gp.group() == 2 ? recoil : 0f);
                Vector3f c = q.transform(new Vector3f(p.cx(), p.cy(), cz), new Vector3f()).add(PIVOT);
                Vector3f half = q.transform(new Vector3f(p.sx() / 2f, p.sy() / 2f, p.sz() / 2f), new Vector3f());
                Vector3f t = c.sub(half);
                BlockDisplay bd = parts.get(i);
                bd.setTransformation(new Transformation(t, new Quaternionf(q),
                        new Vector3f(p.sx(), p.sy(), p.sz()), new Quaternionf()));
                bd.setInterpolationDelay(0);
                bd.setInterpolationDuration(instant ? 0 : 2);
            }
        }

        /** 모든 디스플레이를 현재 방향으로 돌림(엔티티 yaw) */
        void turnAll() {
            Location o = origin();
            Location l = new Location(o.getWorld(), o.getX(), o.getY(), o.getZ(), yaw, 0f);
            for (BlockDisplay d : parts) if (d.isValid()) d.teleport(l);
        }

        void moveSeat() {
            double[] so = off(0, SEAT_Z, yaw);
            Location o = origin();
            Location l = new Location(o.getWorld(), o.getX() + so[0], o.getY() + SEAT_Y, o.getZ() + so[1]);
            Player r = rider();
            l.setYaw(r != null ? r.getLocation().getYaw() : yaw);
            seat.teleport(l, TeleportFlag.EntityState.RETAIN_PASSENGERS);
        }

        Vector direction() {
            double a = Math.toRadians(pitch);
            double[] h = off(0, Math.cos(a), yaw);
            return new Vector(h[0], -Math.sin(a), h[1]);
        }

        Location muzzle() {
            double a = Math.toRadians(pitch);
            double fz = PIVOT.z + MUZZLE * Math.cos(a);
            double fy = PIVOT.y - MUZZLE * Math.sin(a);
            double[] h = off(0, fz, yaw);
            Location o = origin();
            return new Location(o.getWorld(), o.getX() + h[0], o.getY() + fy, o.getZ() + h[1]);
        }

        void mount(Player p) {
            if (rider() != null) {
                p.sendActionBar(Component.text("이미 누군가 앉아 있습니다.", NamedTextColor.RED));
                return;
            }
            if (p.isInsideVehicle()) return;
            moveSeat();
            seat.addPassenger(p);
        }

        boolean hasShell(Player p) {
            for (ItemStack it : p.getInventory().getStorageContents()) if (isMark(it, K_ITEM_SHELL)) return true;
            return isMark(p.getInventory().getItemInOffHand(), K_ITEM_SHELL);
        }

        boolean takeShell(Player p) {
            ItemStack[] cont = p.getInventory().getStorageContents();
            for (int i = 0; i < cont.length; i++) {
                if (isMark(cont[i], K_ITEM_SHELL)) {
                    cont[i].setAmount(cont[i].getAmount() - 1);
                    p.getInventory().setStorageContents(cont);
                    return true;
                }
            }
            ItemStack off = p.getInventory().getItemInOffHand();
            if (isMark(off, K_ITEM_SHELL)) {
                off.setAmount(off.getAmount() - 1);
                return true;
            }
            return false;
        }

        void tick() {
            if (recoil > 0f) {
                recoil = Math.max(0f, recoil - 0.06f);
                applyBarrel(false);
            }
            Player p = rider();
            if (p == null) {
                loading = -1;
                if (dirty && tickCount % 20 == 0) save();
                return;
            }
            // --- 조준 (천천히 따라감) ---
            float ty = p.getLocation().getYaw();
            float tp = clamp(p.getLocation().getPitch(), PITCH_MIN, PITCH_MAX);
            float ny = yaw + clamp(wrap(ty - yaw), -yawSpeed, yawSpeed);
            float np = pitch + clamp(tp - pitch, -pitchSpeed, pitchSpeed);
            if (ny != yaw) {
                yaw = ny;
                turnAll();
                moveSeat();
                dirty = true;
            }
            if (np != pitch) {
                pitch = np;
                applyBarrel(false);
                dirty = true;
            }
            if (dirty && tickCount % 20 == 0) save();

            // --- 장전 (앉은 채로 자동) ---
            if (!loaded) {
                if (loading < 0) {
                    if (hasShell(p)) {
                        loading = 0;
                        origin().getWorld().playSound(origin(), Sound.BLOCK_IRON_TRAPDOOR_OPEN, 1f, 0.8f);
                    }
                } else {
                    loading++;
                    if (loading == loadTicks / 2) {
                        origin().getWorld().playSound(origin(), Sound.BLOCK_PISTON_EXTEND, 0.8f, 1.2f);
                    }
                    if (loading >= loadTicks) {
                        loading = -1;
                        if (takeShell(p)) {
                            loaded = true;
                            save();
                            origin().getWorld().playSound(origin(), Sound.BLOCK_IRON_TRAPDOOR_CLOSE, 1f, 0.7f);
                        }
                    }
                }
            }

            if (tickCount % 3 == 0) {
                Component status;
                if (loaded) {
                    status = Component.text("장전 완료 - 좌클릭으로 발사", NamedTextColor.GREEN);
                } else if (loading >= 0) {
                    int f = Math.min(10, loading * 10 / Math.max(1, loadTicks));
                    status = Component.text("장전 중 " + "▮".repeat(f) + "▯".repeat(10 - f), NamedTextColor.YELLOW);
                } else {
                    status = Component.text("야포탄이 필요합니다", NamedTextColor.RED);
                }
                p.sendActionBar(status);
            }
        }

        void fire(Player p) {
            if (tickCount - lastFire < 4) return;
            if (!loaded) {
                p.sendActionBar(Component.text("아직 장전되지 않았습니다.", NamedTextColor.RED));
                return;
            }
            loaded = false;
            lastFire = tickCount;
            recoil = 0.45f;
            applyBarrel(false);
            save();

            Location mz = muzzle();
            Vector dir = direction();
            World w = mz.getWorld();
            Snowball s = w.spawn(mz.clone().add(dir.clone().multiply(0.3)), Snowball.class, sb -> {
                sb.setItem(shellItem(1));
                sb.setShooter(p);
                sb.setGravity(true);
                sb.setVelocity(dir.clone().multiply(speed));
                sb.setPersistent(false);
                sb.setVisibleByDefault(false);
                sb.getPersistentDataContainer().set(K_PROJ, PersistentDataType.BYTE, (byte) 1);
            });
            ItemDisplay vis = w.spawn(s.getLocation(), ItemDisplay.class, d -> {
                d.setItemStack(shellItem(1));
                d.setBillboard(Display.Billboard.CENTER);
                d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(),
                        new Vector3f(0.5f, 0.5f, 0.5f), new Quaternionf()));
                d.setTeleportDuration(1);
                d.setViewRange(2f);
                d.setPersistent(false);
            });
            shells.put(s, new Shot(vis, s.getLocation()));

            w.spawnParticle(Particle.FLAME, mz, 25, 0.12, 0.12, 0.12, 0.1);
            w.spawnParticle(Particle.LARGE_SMOKE, mz, 20, 0.2, 0.2, 0.2, 0.04);
            w.spawnParticle(Particle.EXPLOSION, mz, 1);
            w.playSound(mz, Sound.ENTITY_GENERIC_EXPLODE, 3f, 0.5f);
            w.playSound(mz, Sound.ENTITY_BLAZE_SHOOT, 1.5f, 0.4f);
            w.playSound(mz, Sound.ENTITY_IRON_GOLEM_HURT, 1f, 0.5f);
        }
    }

    // =====================================================================
    //  생성 / 복구
    // =====================================================================
    Gun spawnGun(Location loc, float yaw) {
        UUID id = UUID.randomUUID();
        String sid = id.toString();
        World w = loc.getWorld();
        Location base = new Location(w, loc.getX(), loc.getY(), loc.getZ(), yaw, 0f);

        Interaction hb = w.spawn(base, Interaction.class, i -> {
            i.setInteractionWidth(1.8f);
            i.setInteractionHeight(1.3f);
            i.setResponsive(true);
            i.getPersistentDataContainer().set(K_ID, PersistentDataType.STRING, sid);
        });
        double[] so = off(0, SEAT_Z, yaw);
        ArmorStand seat = w.spawn(new Location(w, loc.getX() + so[0], loc.getY() + SEAT_Y, loc.getZ() + so[1], yaw, 0f),
                ArmorStand.class, s -> {
                    s.setVisible(false);
                    s.setGravity(false);
                    s.setMarker(true);
                    s.setSmall(true);
                    s.setInvulnerable(true);
                    s.setSilent(true);
                    s.getPersistentDataContainer().set(K_ID, PersistentDataType.STRING, sid);
                });
        List<BlockDisplay> parts = new ArrayList<>();
        for (int i = 0; i < GPS.size(); i++) {
            final GP gp = GPS.get(i);
            final int idx = i;
            parts.add(w.spawn(base, BlockDisplay.class, d -> {
                d.setBlock(Bukkit.createBlockData(gp.p().data()));
                d.setTeleportDuration(3);
                d.setViewRange(1.5f);
                d.getPersistentDataContainer().set(K_ID, PersistentDataType.STRING, sid);
                d.getPersistentDataContainer().set(K_PART, PersistentDataType.INTEGER, idx);
            }));
        }
        Gun g = new Gun(id, hb, seat, parts, yaw, 0f, false);
        guns.put(id, g);
        g.applyStatic();
        g.applyBarrel(true);
        g.moveSeat();
        g.save();
        return g;
    }

    void tryRegister(Interaction hb, int attempt) {
        if (!hb.isValid()) return;
        PersistentDataContainer hp = hb.getPersistentDataContainer();
        String sid = hp.get(K_ID, PersistentDataType.STRING);
        if (sid == null) return;
        UUID id;
        try {
            id = UUID.fromString(sid);
        } catch (IllegalArgumentException ex) {
            return;
        }
        if (guns.containsKey(id)) return;

        ArmorStand seat = null;
        TreeMap<Integer, BlockDisplay> pm = new TreeMap<>();
        for (Entity en : hb.getNearbyEntities(5, 4, 5)) {
            PersistentDataContainer p = en.getPersistentDataContainer();
            if (!sid.equals(p.get(K_ID, PersistentDataType.STRING))) continue;
            if (en instanceof ArmorStand a) seat = a;
            else if (en instanceof BlockDisplay b) {
                Integer idx = p.get(K_PART, PersistentDataType.INTEGER);
                if (idx != null) pm.put(idx, b);
            }
        }
        if (seat == null || pm.size() != GPS.size()) {
            if (attempt < 5) {
                final int next = attempt + 1;
                Bukkit.getScheduler().runTaskLater(plugin, () -> tryRegister(hb, next), 20L);
            }
            return;
        }
        Byte loaded = hp.get(K_LOADED, PersistentDataType.BYTE);
        Float yaw = hp.get(K_YAW, PersistentDataType.FLOAT);
        Float pitch = hp.get(K_PITCH, PersistentDataType.FLOAT);
        Gun g = new Gun(id, hb, seat, new ArrayList<>(pm.values()),
                yaw == null ? 0f : yaw, pitch == null ? 0f : pitch, loaded != null && loaded == 1);
        guns.put(id, g);
        g.applyBarrel(true);
        g.turnAll();
        g.moveSeat();
    }

    // =====================================================================
    //  틱
    // =====================================================================
    void tick() {
        tickCount++;
        Iterator<Gun> it = guns.values().iterator();
        while (it.hasNext()) {
            Gun g = it.next();
            if (!g.hitbox.isValid()) {
                it.remove();
                continue;
            }
            g.tick();
        }
        Iterator<Map.Entry<Snowball, Shot>> bi = shells.entrySet().iterator();
        while (bi.hasNext()) {
            Map.Entry<Snowball, Shot> be = bi.next();
            Snowball s = be.getKey();
            Shot shot = be.getValue();
            if (!s.isValid()) {
                shot.vis.remove();
                bi.remove();
                continue;
            }
            Location now = s.getLocation();
            if (shot.vis.isValid()) shot.vis.teleport(now);
            Location prev = shot.last;
            if (prev != null && prev.getWorld() == now.getWorld()) {
                Vector seg = now.toVector().subtract(prev.toVector());
                int n = (int) Math.min(12, Math.max(1, Math.ceil(seg.length() / 0.4)));
                for (int i = 1; i <= n; i++) {
                    Location pt = prev.clone().add(seg.clone().multiply((double) i / n));
                    now.getWorld().spawnParticle(Particle.SMOKE, pt, 1, 0.03, 0.03, 0.03, 0);
                }
            }
            shot.last = now;
            if (s.getTicksLived() > 300) {
                shot.vis.remove();
                bi.remove();
                Object sh = s.getShooter();
                s.remove();
                plugin.explode(now, sh instanceof Entity en ? en : null, power, blastScale);
            }
        }
    }

    // =====================================================================
    //  이벤트
    // =====================================================================
    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        Action a = e.getAction();
        if ((a == Action.LEFT_CLICK_AIR || a == Action.LEFT_CLICK_BLOCK) && e.getHand() == EquipmentSlot.HAND) {
            Gun g = gunOf(p);
            if (g != null) {
                e.setCancelled(true);
                g.fire(p);
                return;
            }
        }
        ItemStack it = e.getItem();
        if (it == null) return;
        if (isMark(it, K_ITEM_SHELL)) {
            if (a == Action.RIGHT_CLICK_BLOCK || a == Action.RIGHT_CLICK_AIR) e.setCancelled(true);
            return;
        }
        if (a == Action.RIGHT_CLICK_BLOCK && e.getHand() == EquipmentSlot.HAND && isMark(it, K_ITEM_GUN)) {
            e.setCancelled(true);
            if (e.getClickedBlock() == null) return;
            Block target = e.getClickedBlock().getRelative(e.getBlockFace());
            if (!target.isPassable() || !target.getRelative(BlockFace.UP).isPassable()
                    || !target.getRelative(BlockFace.UP, 2).isPassable()) {
                p.sendActionBar(Component.text("여기엔 설치할 수 없습니다.", NamedTextColor.RED));
                return;
            }
            Location loc = target.getLocation().add(0.5, 0, 0.5);
            spawnGun(loc, p.getLocation().getYaw());
            loc.getWorld().playSound(loc, Sound.BLOCK_ANVIL_PLACE, 1f, 0.6f);
            if (p.getGameMode() != GameMode.CREATIVE) it.setAmount(it.getAmount() - 1);
        }
    }

    @EventHandler
    public void onClickEntity(PlayerInteractEntityEvent e) {
        Gun g = gunOfEntity(e.getRightClicked());
        if (g == null) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        g.mount(e.getPlayer());
    }

    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        Entity en = e.getEntity();
        if (!isGunEntity(en)) return;
        e.setCancelled(true);
        if (e instanceof EntityDamageByEntityEvent d && d.getDamager() instanceof Player p) tryPickup(p, en);
    }

    @EventHandler
    public void onPreAttack(PrePlayerAttackEntityEvent e) {
        if (!isGunEntity(e.getAttacked())) return;
        e.setCancelled(true);
        tryPickup(e.getPlayer(), e.getAttacked());
    }

    void tryPickup(Player p, Entity en) {
        Gun g = gunOfEntity(en);
        if (g == null) return;
        Player rider = g.rider();
        if (rider != null) {
            if (rider.equals(p)) g.fire(p);
            else p.sendActionBar(Component.text("누군가 사용 중입니다.", NamedTextColor.RED));
            return;
        }
        if (!p.isSneaking()) {
            p.sendActionBar(Component.text("웅크린 채로 때리면 야포를 회수합니다.", NamedTextColor.YELLOW));
            return;
        }
        Location l = g.origin().add(0, 0.5, 0);
        l.getWorld().dropItemNaturally(l, gunItem());
        if (g.loaded) l.getWorld().dropItemNaturally(l, shellItem(1));
        g.remove();
        guns.remove(g.id);
    }

    @EventHandler
    public void onHit(ProjectileHitEvent e) {
        Projectile pr = e.getEntity();
        if (!pr.getPersistentDataContainer().has(K_PROJ, PersistentDataType.BYTE)) return;
        Entity he = e.getHitEntity();
        if (he != null) {
            if (isGunEntity(he) || (he.equals(pr.getShooter()) && pr.getTicksLived() < 10)) {
                e.setCancelled(true);
                return;
            }
        }
        Location loc = pr.getLocation();
        if (e.getHitBlockFace() != null) loc.add(e.getHitBlockFace().getDirection().multiply(0.4));
        Object shooter = pr.getShooter();
        if (pr instanceof Snowball sb) {
            Shot shot = shells.remove(sb);
            if (shot != null) shot.vis.remove();
        }
        pr.remove();
        plugin.explode(loc, shooter instanceof Entity en ? en : null, power, blastScale);
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        for (Entity en : e.getEntities()) {
            if (en instanceof Interaction hb && isGunEntity(en)) {
                Bukkit.getScheduler().runTask(plugin, () -> tryRegister(hb, 0));
            }
        }
    }

    @EventHandler
    public void onEntitiesUnload(EntitiesUnloadEvent e) {
        for (Entity en : e.getEntities()) {
            if (en instanceof Interaction && isGunEntity(en)) {
                Gun g = gunOfEntity(en);
                if (g != null) {
                    g.save();
                    guns.remove(g.id);
                }
            }
        }
    }
}
