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
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.regex.Pattern;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

public final class CannonPlugin extends JavaPlugin implements Listener {

    // ---------- 모델 정의 (카빈/포신) ----------
    record Def(Material mat, boolean barrel, float cx, float cy, float cz, float sx, float sy, float sz) {}

    /** 포신 회전 중심(포대 기준) */
    static final Vector3f PIVOT = new Vector3f(0f, 0.85f, 0.1f);

    static final List<Def> DEFS = List.of(
            // --- 포대 (좌우 회전만) ---
            new Def(Material.DARK_OAK_PLANKS, false, -0.55f, 0.40f, 0.00f, 0.18f, 0.80f, 0.80f), // 왼쪽 바퀴
            new Def(Material.DARK_OAK_PLANKS, false, 0.55f, 0.40f, 0.00f, 0.18f, 0.80f, 0.80f),  // 오른쪽 바퀴
            new Def(Material.IRON_BLOCK, false, -0.66f, 0.40f, 0.00f, 0.08f, 0.26f, 0.26f),      // 바퀴 축 장식
            new Def(Material.IRON_BLOCK, false, 0.66f, 0.40f, 0.00f, 0.08f, 0.26f, 0.26f),
            new Def(Material.STRIPPED_SPRUCE_LOG, false, 0.00f, 0.40f, 0.00f, 1.00f, 0.14f, 0.14f), // 차축
            new Def(Material.SPRUCE_PLANKS, false, -0.32f, 0.75f, 0.10f, 0.16f, 0.55f, 0.70f),   // 받침대
            new Def(Material.SPRUCE_PLANKS, false, 0.32f, 0.75f, 0.10f, 0.16f, 0.55f, 0.70f),
            new Def(Material.SPRUCE_PLANKS, false, 0.00f, 0.14f, -0.75f, 0.30f, 0.14f, 0.90f),   // 꼬리
            // --- 포신 (피벗 기준, +Z가 앞) ---
            new Def(Material.BLACKSTONE, true, 0.00f, 0.00f, 0.50f, 0.50f, 0.50f, 1.50f),       // 관
            new Def(Material.BLACKSTONE, true, 0.00f, 0.00f, -0.35f, 0.42f, 0.42f, 0.30f),      // 포미
            new Def(Material.IRON_BLOCK, true, 0.00f, 0.00f, 0.00f, 0.58f, 0.58f, 0.12f),       // 보강대
            new Def(Material.IRON_BLOCK, true, 0.00f, 0.00f, 0.60f, 0.56f, 0.56f, 0.12f),
            new Def(Material.IRON_BLOCK, true, 0.00f, 0.00f, 1.20f, 0.62f, 0.62f, 0.18f),       // 포구 링
            new Def(Material.IRON_BLOCK, true, 0.00f, 0.00f, -0.60f, 0.20f, 0.20f, 0.20f),      // 꼭지
            new Def(Material.BLACK_CONCRETE, true, 0.00f, 0.00f, 1.30f, 0.28f, 0.28f, 0.05f)    // 포구 구멍
    );

    static final float PITCH_MIN = -60f; // 위
    static final float PITCH_MAX = 15f;  // 아래

    // ---------- 설정 ----------
    record CItem(String id) {}

    CItem steel, bolt, gearbox, oilItem;
    int loadTicks, maxOil, oilPerShot;
    float yawSpeed, pitchSpeed, projSpeed, explosionPower;

    // ---------- 키 ----------
    NamespacedKey K_CANNON_ID, K_OIL, K_LOADED, K_YAW, K_PITCH, K_PART, K_SEAT;
    NamespacedKey K_ITEM_CANNON, K_ITEM_BALL, K_PROJ;

    final Map<UUID, Cannon> cannons = new HashMap<>();
    final Set<Snowball> balls = new HashSet<>();
    long tickCount = 0;

    // =====================================================================
    @Override
    public void onEnable() {
        saveDefaultConfig();
        K_CANNON_ID = new NamespacedKey(this, "cannon_id");
        K_OIL = new NamespacedKey(this, "oil");
        K_LOADED = new NamespacedKey(this, "loaded");
        K_YAW = new NamespacedKey(this, "yaw");
        K_PITCH = new NamespacedKey(this, "pitch");
        K_PART = new NamespacedKey(this, "part");
        K_SEAT = new NamespacedKey(this, "seat");
        K_ITEM_CANNON = new NamespacedKey(this, "item_cannon");
        K_ITEM_BALL = new NamespacedKey(this, "item_ball");
        K_PROJ = new NamespacedKey(this, "projectile");

        loadSettings();
        Bukkit.getPluginManager().registerEvents(this, this);

        Bukkit.getScheduler().runTaskTimer(this, this::globalTick, 1L, 1L);

        // 이미 로드된 청크의 대포 복구
        for (World w : Bukkit.getWorlds()) {
            for (Interaction hb : w.getEntitiesByClass(Interaction.class)) {
                tryRegister(hb, 0);
            }
        }
    }

    @Override
    public void onDisable() {
        for (Cannon c : cannons.values()) c.save();
        cannons.clear();
        for (Snowball s : balls) s.remove();
        balls.clear();
    }

    void loadSettings() {
        steel = readItem("steel");
        bolt = readItem("steel_bolt");
        gearbox = readItem("gearbox");
        oilItem = readItem("refined_oil");
        loadTicks = getConfig().getInt("cannon.load-seconds", 5) * 20;
        maxOil = getConfig().getInt("cannon.max-oil", 10);
        oilPerShot = getConfig().getInt("cannon.oil-per-shot", 1);
        yawSpeed = (float) getConfig().getDouble("cannon.turn-speed-yaw", 2.0);
        pitchSpeed = (float) getConfig().getDouble("cannon.turn-speed-pitch", 1.2);
        projSpeed = (float) getConfig().getDouble("cannon.projectile-speed", 2.5);
        explosionPower = (float) getConfig().getDouble("cannon.explosion-power", 4.0);
    }

    CItem readItem(String name) {
        return new CItem(getConfig().getString("items." + name, name));
    }

    // =====================================================================
    //  아이템
    // =====================================================================
    ItemStack cannonItem(int oil) {
        ItemStack it = new ItemStack(Material.DISPENSER);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text("대포", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(
                Component.text("기름: " + oil + "/" + maxOil + "L", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("우클릭으로 설치", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)));
        m.getPersistentDataContainer().set(K_ITEM_CANNON, PersistentDataType.BYTE, (byte) 1);
        m.getPersistentDataContainer().set(K_OIL, PersistentDataType.INTEGER, oil);
        m.setMaxStackSize(1);
        it.setItemMeta(m);
        return it;
    }

    ItemStack ballItem(int amount) {
        ItemStack it = new ItemStack(Material.FIRE_CHARGE, amount);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text("대포알", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        m.getPersistentDataContainer().set(K_ITEM_BALL, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    boolean isMark(ItemStack it, NamespacedKey key) {
        if (it == null || !it.hasItemMeta()) return false;
        return it.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    /** 다른 플러그인 아이템 판별: item_model / 모델데이터 문자열 / PDC / 컴포넌트 전체 문자열에서 id를 찾음 */
    boolean is(ItemStack it, CItem c) {
        if (it == null || it.getType().isAir()) return false;
        ItemMeta m = it.getItemMeta();
        if (m == null) return false;
        if (m.hasItemModel() && m.getItemModel().toString().equals(c.id())) return true;
        PersistentDataContainer p = m.getPersistentDataContainer();
        for (NamespacedKey k : p.getKeys()) {
            if (k.toString().equals(c.id())) return true;
            try {
                String s = p.get(k, PersistentDataType.STRING);
                if (c.id().equals(s)) return true;
            } catch (IllegalArgumentException ignored) {
            }
        }
        try {
            return containsId(m.getAsComponentString(), c.id());
        } catch (Throwable t) {
            return false;
        }
    }

    /** "machines:steel" 이 "machines:steel_bolt" 에 걸리지 않도록 경계 검사 */
    static boolean containsId(String text, String id) {
        if (text == null) return false;
        return Pattern.compile("(?<![a-z0-9_.\\-/:])" + Pattern.quote(id) + "(?![a-z0-9_.\\-/])").matcher(text).find();
    }

    // =====================================================================
    //  조합법 (바닐라 레시피 대신 직접 검사 -> 다른 플러그인 아이템 id 그대로 사용)
    // =====================================================================
    /** 3x3 그리드가 대포/대포알 조합이면 결과 아이템, 아니면 null */
    ItemStack craftResult(ItemStack[] m) {
        if (m == null || m.length < 9) return null;
        // 대포알: 윗줄·아랫줄 강철 / 가운데 줄 화약 (책장 모양)
        boolean ball = true;
        for (int i : new int[]{0, 1, 2, 6, 7, 8}) if (!is(m[i], steel)) ball = false;
        for (int i : new int[]{3, 4, 5}) if (m[i] == null || m[i].getType() != Material.GUNPOWDER) ball = false;
        if (ball) return ballItem(1);
        // 대포: 1,7 네더라이트 블럭 / 2,8 강철 볼트 / 3 라이터 / 5 기어박스 / 6,9 나무판자
        boolean cannon = true;
        for (int i : new int[]{0, 6}) if (m[i] == null || m[i].getType() != Material.NETHERITE_BLOCK) cannon = false;
        if (!is(m[1], bolt) || !is(m[7], bolt)) cannon = false;
        if (m[2] == null || m[2].getType() != Material.FLINT_AND_STEEL) cannon = false;
        if (m[3] != null && !m[3].getType().isAir()) cannon = false;
        if (!is(m[4], gearbox)) cannon = false;
        for (int i : new int[]{5, 8}) if (m[i] == null || !Tag.PLANKS.isTagged(m[i].getType())) cannon = false;
        if (cannon) return cannonItem(0);
        return null;
    }

    @EventHandler
    public void onPrepare(PrepareItemCraftEvent e) {
        ItemStack r = craftResult(e.getInventory().getMatrix());
        if (r != null) e.getInventory().setResult(r);
    }

    @EventHandler
    public void onCraftClick(InventoryClickEvent e) {
        if (!(e.getInventory() instanceof CraftingInventory inv)) return;
        if (e.getSlotType() != InventoryType.SlotType.RESULT) return;
        ItemStack[] m = inv.getMatrix();
        ItemStack result = craftResult(m);
        if (result == null) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;

        boolean shift = e.isShiftClick();
        if (!shift && !e.isLeftClick() && !e.isRightClick()) return;

        int times = 1;
        if (shift) {
            times = Integer.MAX_VALUE;
            for (ItemStack it : m) if (it != null && !it.getType().isAir()) times = Math.min(times, it.getAmount());
        } else {
            ItemStack cur = e.getCursor();
            if (cur != null && !cur.getType().isAir()) {
                if (!(cur.isSimilar(result) && cur.getAmount() + result.getAmount() <= cur.getMaxStackSize())) return;
            }
        }

        int made = 0;
        for (int i = 0; i < times; i++) {
            if (shift) {
                if (!p.getInventory().addItem(result.clone()).isEmpty()) break;
            }
            made++;
        }
        if (made == 0) return;
        if (!shift) {
            ItemStack cur = e.getCursor();
            if (cur == null || cur.getType().isAir()) p.setItemOnCursor(result.clone());
            else {
                cur.setAmount(cur.getAmount() + result.getAmount());
                p.setItemOnCursor(cur);
            }
        }
        for (int i = 0; i < m.length; i++) {
            if (m[i] != null && !m[i].getType().isAir()) {
                m[i].setAmount(m[i].getAmount() - made);
                if (m[i].getAmount() <= 0) m[i] = null;
            }
        }
        inv.setMatrix(m);
        inv.setResult(craftResult(m));
        Bukkit.getScheduler().runTask(this, p::updateInventory);
    }

    // =====================================================================
    //  대포 설치 / 상호작용
    // =====================================================================
    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        Action a = e.getAction();

        // 발사 (앉은 상태에서 좌클릭)
        if ((a == Action.LEFT_CLICK_AIR || a == Action.LEFT_CLICK_BLOCK) && e.getHand() == EquipmentSlot.HAND) {
            Cannon c = cannonOf(p);
            if (c != null) {
                e.setCancelled(true);
                c.fire(p);
                return;
            }
        }

        ItemStack it = e.getItem();
        if (it == null) return;

        if (isMark(it, K_ITEM_BALL)) { // 대포알로 불 붙이기 방지
            if (a == Action.RIGHT_CLICK_BLOCK || a == Action.RIGHT_CLICK_AIR) e.setCancelled(true);
            return;
        }

        if (a == Action.RIGHT_CLICK_BLOCK && e.getHand() == EquipmentSlot.HAND && isMark(it, K_ITEM_CANNON)) {
            e.setCancelled(true);
            Block target = e.getClickedBlock().getRelative(e.getBlockFace());
            if (!target.isPassable() || !target.getRelative(BlockFace.UP).isPassable()) {
                p.sendActionBar(Component.text("여기엔 설치할 수 없습니다.", NamedTextColor.RED));
                return;
            }
            Integer oil = it.getItemMeta().getPersistentDataContainer().get(K_OIL, PersistentDataType.INTEGER);
            Location loc = target.getLocation().add(0.5, 0, 0.5);
            spawnCannon(loc, p.getLocation().getYaw(), oil == null ? 0 : oil);
            loc.getWorld().playSound(loc, Sound.BLOCK_ANVIL_PLACE, 0.8f, 0.8f);
            if (p.getGameMode() != GameMode.CREATIVE) it.setAmount(it.getAmount() - 1);
        }
    }

    @EventHandler
    public void onClickEntity(PlayerInteractEntityEvent e) {
        Cannon c = cannonOfEntity(e.getRightClicked());
        if (c == null) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        ItemStack held = p.getInventory().getItemInMainHand();
        if (is(held, oilItem)) {
            c.addOil(p, held);
        } else {
            c.mount(p);
        }
    }

    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        Entity en = e.getEntity();
        if (!isCannonEntity(en)) return;
        e.setCancelled(true);
        if (e instanceof EntityDamageByEntityEvent d && d.getDamager() instanceof Player p) {
            tryPickup(p, en);
        }
    }

    @EventHandler
    public void onPreAttack(PrePlayerAttackEntityEvent e) {
        if (!isCannonEntity(e.getAttacked())) return;
        e.setCancelled(true);
        tryPickup(e.getPlayer(), e.getAttacked());
    }

    void tryPickup(Player p, Entity en) {
        Cannon c = cannonOfEntity(en);
        if (c == null) return;
        if (c.rider() != null) {
            p.sendActionBar(Component.text("누군가 사용 중입니다.", NamedTextColor.RED));
            return;
        }
        if (!p.isSneaking()) {
            p.sendActionBar(Component.text("웅크린 채로 때리면 대포를 회수합니다.", NamedTextColor.YELLOW));
            return;
        }
        Location l = c.origin();
        l.getWorld().dropItemNaturally(l.add(0, 0.5, 0), cannonItem(c.oil));
        c.remove();
        cannons.remove(c.id);
    }

    boolean isCannonEntity(Entity en) {
        return en.getPersistentDataContainer().has(K_CANNON_ID, PersistentDataType.STRING);
    }

    Cannon cannonOfEntity(Entity en) {
        String s = en.getPersistentDataContainer().get(K_CANNON_ID, PersistentDataType.STRING);
        if (s == null) return null;
        try {
            return cannons.get(UUID.fromString(s));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    Cannon cannonOf(Player p) {
        Entity v = p.getVehicle();
        return v == null ? null : cannonOfEntity(v);
    }

    // =====================================================================
    //  포탄
    // =====================================================================
    @EventHandler
    public void onHit(ProjectileHitEvent e) {
        Projectile pr = e.getEntity();
        if (!pr.getPersistentDataContainer().has(K_PROJ, PersistentDataType.BYTE)) return;
        Entity he = e.getHitEntity();
        if (he != null) {
            if (isCannonEntity(he) || (he.equals(pr.getShooter()) && pr.getTicksLived() < 10)) {
                e.setCancelled(true);
                return;
            }
        }
        Location loc = pr.getLocation();
        if (e.getHitBlockFace() != null) loc.add(e.getHitBlockFace().getDirection().multiply(0.4));
        Object shooter = pr.getShooter();
        balls.remove(pr);
        pr.remove();
        explode(loc, shooter instanceof Entity en ? en : null);
    }

    void explode(Location loc, Entity source) {
        // 블럭 파괴 X, 불 X, 엔티티 피해만
        loc.getWorld().createExplosion(loc, explosionPower, false, false, source);
    }

    void globalTick() {
        tickCount++;
        Iterator<Cannon> it = cannons.values().iterator();
        while (it.hasNext()) {
            Cannon c = it.next();
            if (!c.hitbox.isValid()) {
                it.remove();
                continue;
            }
            c.tick();
        }
        Iterator<Snowball> bi = balls.iterator();
        while (bi.hasNext()) {
            Snowball s = bi.next();
            if (!s.isValid()) {
                bi.remove();
                continue;
            }
            s.getWorld().spawnParticle(Particle.SMOKE, s.getLocation(), 2, 0.05, 0.05, 0.05, 0);
            if (s.getTicksLived() > 200) {
                bi.remove();
                Location l = s.getLocation();
                Object sh = s.getShooter();
                s.remove();
                explode(l, sh instanceof Entity en ? en : null);
            }
        }
    }

    // =====================================================================
    //  생성 / 복구
    // =====================================================================
    Cannon spawnCannon(Location loc, float yaw, int oil) {
        UUID id = UUID.randomUUID();
        String sid = id.toString();
        World w = loc.getWorld();

        Interaction hb = w.spawn(loc, Interaction.class, i -> {
            i.setInteractionWidth(1.4f);
            i.setInteractionHeight(1.1f);
            i.setResponsive(true);
            i.getPersistentDataContainer().set(K_CANNON_ID, PersistentDataType.STRING, sid);
        });
        ArmorStand seat = w.spawn(loc, ArmorStand.class, s -> {
            s.setVisible(false);
            s.setGravity(false);
            s.setMarker(true);
            s.setSmall(true);
            s.setInvulnerable(true);
            s.setSilent(true);
            s.getPersistentDataContainer().set(K_CANNON_ID, PersistentDataType.STRING, sid);
            s.getPersistentDataContainer().set(K_SEAT, PersistentDataType.BYTE, (byte) 1);
        });
        List<BlockDisplay> parts = new ArrayList<>();
        for (int i = 0; i < DEFS.size(); i++) {
            final int idx = i;
            parts.add(w.spawn(loc, BlockDisplay.class, d -> {
                d.setBlock(DEFS.get(idx).mat().createBlockData());
                d.getPersistentDataContainer().set(K_CANNON_ID, PersistentDataType.STRING, sid);
                d.getPersistentDataContainer().set(K_PART, PersistentDataType.INTEGER, idx);
            }));
        }
        Cannon c = new Cannon(id, hb, seat, parts, yaw, 0f, oil, false);
        cannons.put(id, c);
        c.applyVisual(true);
        c.moveSeat();
        c.save();
        return c;
    }

    void tryRegister(Interaction hb, int attempt) {
        if (!hb.isValid()) return;
        PersistentDataContainer hp = hb.getPersistentDataContainer();
        String sid = hp.get(K_CANNON_ID, PersistentDataType.STRING);
        if (sid == null) return;
        UUID id;
        try {
            id = UUID.fromString(sid);
        } catch (IllegalArgumentException ex) {
            return;
        }
        if (cannons.containsKey(id)) return;

        ArmorStand seat = null;
        TreeMap<Integer, BlockDisplay> pm = new TreeMap<>();
        for (Entity en : hb.getNearbyEntities(3, 3, 3)) {
            PersistentDataContainer p = en.getPersistentDataContainer();
            if (!sid.equals(p.get(K_CANNON_ID, PersistentDataType.STRING))) continue;
            if (en instanceof ArmorStand a) seat = a;
            else if (en instanceof BlockDisplay b) {
                Integer idx = p.get(K_PART, PersistentDataType.INTEGER);
                if (idx != null) pm.put(idx, b);
            }
        }
        if (seat == null || pm.size() != DEFS.size()) {
            if (attempt < 5) {
                final int next = attempt + 1;
                Bukkit.getScheduler().runTaskLater(this, () -> tryRegister(hb, next), 20L);
            }
            return;
        }
        Integer oil = hp.get(K_OIL, PersistentDataType.INTEGER);
        Byte loaded = hp.get(K_LOADED, PersistentDataType.BYTE);
        Float yaw = hp.get(K_YAW, PersistentDataType.FLOAT);
        Float pitch = hp.get(K_PITCH, PersistentDataType.FLOAT);
        Cannon c = new Cannon(id, hb, seat, new ArrayList<>(pm.values()),
                yaw == null ? 0f : yaw, pitch == null ? 0f : pitch,
                oil == null ? 0 : oil, loaded != null && loaded == 1);
        cannons.put(id, c);
        c.applyVisual(true);
        c.moveSeat();
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        for (Entity en : e.getEntities()) {
            if (en instanceof Interaction hb) {
                Bukkit.getScheduler().runTask(this, () -> tryRegister(hb, 0));
            }
        }
    }

    @EventHandler
    public void onEntitiesUnload(EntitiesUnloadEvent e) {
        for (Entity en : e.getEntities()) {
            if (en instanceof Interaction) {
                Cannon c = cannonOfEntity(en);
                if (c != null) {
                    c.save();
                    cannons.remove(c.id);
                }
            }
        }
    }

    // =====================================================================
    //  명령어
    // =====================================================================
    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!(s instanceof Player p)) return true;
        if (a.length >= 1 && a[0].equalsIgnoreCase("id")) {
            ItemStack h = p.getInventory().getItemInMainHand();
            if (h.getType().isAir()) {
                p.sendMessage("손에 아이템을 들고 입력하세요.");
            } else {
                p.sendMessage("재질: " + h.getType());
                p.sendMessage("데이터: " + h.getItemMeta().getAsComponentString());
                p.sendMessage("강철:" + is(h, steel) + " 볼트:" + is(h, bolt) + " 기어박스:" + is(h, gearbox) + " 정제유:" + is(h, oilItem));
            }
            return true;
        }
        if (a.length >= 2 && a[0].equalsIgnoreCase("give")) {
            int amt = 1;
            if (a.length >= 3) {
                try {
                    amt = Math.max(1, Math.min(64, Integer.parseInt(a[2])));
                } catch (NumberFormatException ignored) {
                }
            }
            if (a[1].equalsIgnoreCase("cannon")) p.getInventory().addItem(cannonItem(0));
            else if (a[1].equalsIgnoreCase("ball")) p.getInventory().addItem(ballItem(amt));
            else return false;
            return true;
        }
        return false;
    }

    // =====================================================================
    //  대포 객체
    // =====================================================================
    static float wrap(float a) {
        a %= 360f;
        if (a >= 180f) a -= 360f;
        if (a < -180f) a += 360f;
        return a;
    }

    static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    final class Cannon {
        final UUID id;
        final Interaction hitbox;
        final ArmorStand seat;
        final List<BlockDisplay> parts;
        float yaw, pitch;
        int oil;
        boolean loaded;
        int loading = -1;
        boolean dirty = false;

        Cannon(UUID id, Interaction hb, ArmorStand seat, List<BlockDisplay> parts, float yaw, float pitch, int oil, boolean loaded) {
            this.id = id;
            this.hitbox = hb;
            this.seat = seat;
            this.parts = parts;
            this.yaw = yaw;
            this.pitch = pitch;
            this.oil = oil;
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
            p.set(K_OIL, PersistentDataType.INTEGER, oil);
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

        Quaternionf qYaw() {
            return new Quaternionf().rotationY((float) Math.toRadians(-yaw));
        }

        Quaternionf qBarrel() {
            return new Quaternionf().rotationYXZ((float) Math.toRadians(-yaw), (float) Math.toRadians(pitch), 0f);
        }

        void applyVisual(boolean instant) {
            Quaternionf qy = qYaw();
            Quaternionf q = qBarrel();
            for (int i = 0; i < DEFS.size(); i++) {
                Def d = DEFS.get(i);
                BlockDisplay bd = parts.get(i);
                Vector3f c = new Vector3f(d.cx(), d.cy(), d.cz()).sub(d.sx() / 2f, d.sy() / 2f, d.sz() / 2f);
                Vector3f t;
                Quaternionf rot;
                if (d.barrel()) {
                    rot = q;
                    t = q.transform(c, new Vector3f()).add(qy.transform(new Vector3f(PIVOT), new Vector3f()));
                } else {
                    rot = qy;
                    t = qy.transform(c, new Vector3f());
                }
                bd.setTransformation(new Transformation(t, new Quaternionf(rot),
                        new Vector3f(d.sx(), d.sy(), d.sz()), new Quaternionf()));
                bd.setInterpolationDelay(0);
                bd.setInterpolationDuration(instant ? 0 : 3);
            }
        }

        void moveSeat() {
            Vector3f v = qYaw().transform(new Vector3f(0f, 0.15f, -0.85f), new Vector3f());
            Location l = origin().add(v.x, v.y, v.z);
            Player r = rider();
            l.setYaw(r != null ? r.getLocation().getYaw() : yaw);
            seat.teleport(l, TeleportFlag.EntityState.RETAIN_PASSENGERS);
        }

        Vector direction() {
            Vector3f d = qBarrel().transform(new Vector3f(0f, 0f, 1f), new Vector3f());
            return new Vector(d.x, d.y, d.z);
        }

        Location muzzle() {
            Vector3f v = qBarrel().transform(new Vector3f(0f, 0f, 1.45f), new Vector3f())
                    .add(qYaw().transform(new Vector3f(PIVOT), new Vector3f()));
            return origin().add(v.x, v.y, v.z);
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

        void addOil(Player p, ItemStack held) {
            int space = maxOil - oil;
            if (space <= 0) {
                p.sendActionBar(Component.text("정제유가 가득 찼습니다. (" + oil + "/" + maxOil + "L)", NamedTextColor.YELLOW));
                return;
            }
            int n = Math.min(space, held.getAmount());
            held.setAmount(held.getAmount() - n);
            oil += n;
            save();
            origin().getWorld().playSound(origin(), Sound.ITEM_BUCKET_EMPTY, 0.8f, 1f);
            p.sendActionBar(Component.text("정제유 +" + n + "L  (" + oil + "/" + maxOil + "L)", NamedTextColor.GREEN));
        }

        boolean hasBall(Player p) {
            for (ItemStack it : p.getInventory().getStorageContents()) if (isMark(it, K_ITEM_BALL)) return true;
            return isMark(p.getInventory().getItemInOffHand(), K_ITEM_BALL);
        }

        boolean takeBall(Player p) {
            ItemStack[] cont = p.getInventory().getStorageContents();
            for (int i = 0; i < cont.length; i++) {
                if (isMark(cont[i], K_ITEM_BALL)) {
                    cont[i].setAmount(cont[i].getAmount() - 1);
                    p.getInventory().setStorageContents(cont);
                    return true;
                }
            }
            ItemStack off = p.getInventory().getItemInOffHand();
            if (isMark(off, K_ITEM_BALL)) {
                off.setAmount(off.getAmount() - 1);
                return true;
            }
            return false;
        }

        void tick() {
            Player p = rider();
            if (p == null) {
                loading = -1;
                if (dirty && tickCount % 20 == 0) save();
                return;
            }
            // --- 느린 조준 ---
            float ty = p.getLocation().getYaw();
            float tp = clamp(p.getLocation().getPitch(), PITCH_MIN, PITCH_MAX);
            float ny = yaw + clamp(wrap(ty - yaw), -yawSpeed, yawSpeed);
            float np = pitch + clamp(tp - pitch, -pitchSpeed, pitchSpeed);
            if (ny != yaw || np != pitch) {
                yaw = ny;
                pitch = np;
                dirty = true;
                applyVisual(false);
                moveSeat();
            }
            if (dirty && tickCount % 20 == 0) save();

            // --- 장전 (앉은 채로) ---
            if (!loaded) {
                if (loading < 0) {
                    if (hasBall(p)) {
                        loading = 0;
                        origin().getWorld().playSound(origin(), Sound.BLOCK_CHAIN_PLACE, 1f, 0.7f);
                    }
                } else {
                    loading++;
                    if (loading % 20 == 0) {
                        origin().getWorld().playSound(origin(), Sound.BLOCK_CHAIN_STEP, 1f, 0.8f);
                    }
                    if (loading >= loadTicks) {
                        loading = -1;
                        if (takeBall(p)) {
                            loaded = true;
                            save();
                            origin().getWorld().playSound(origin(), Sound.BLOCK_IRON_DOOR_CLOSE, 1f, 0.6f);
                        }
                    }
                }
            }

            // --- 액션바 ---
            if (tickCount % 3 == 0) {
                Component status;
                if (loaded) {
                    status = Component.text("장전 완료 - 좌클릭으로 발사", NamedTextColor.GREEN);
                } else if (loading >= 0) {
                    int f = Math.min(10, loading * 10 / Math.max(1, loadTicks));
                    status = Component.text("장전 중 " + "▮".repeat(f) + "▯".repeat(10 - f), NamedTextColor.YELLOW);
                } else {
                    status = Component.text("대포알이 필요합니다", NamedTextColor.RED);
                }
                Component oilC = Component.text("  |  기름 " + oil + "/" + maxOil + "L",
                        oil >= oilPerShot ? NamedTextColor.AQUA : NamedTextColor.RED);
                p.sendActionBar(status.append(oilC));
            }
        }

        void fire(Player p) {
            if (!loaded) {
                p.sendActionBar(Component.text("아직 장전되지 않았습니다.", NamedTextColor.RED));
                return;
            }
            if (oil < oilPerShot) {
                p.sendActionBar(Component.text("정제유가 부족합니다!", NamedTextColor.RED));
                return;
            }
            oil -= oilPerShot;
            loaded = false;
            save();

            Location mz = muzzle();
            Vector dir = direction();
            World w = mz.getWorld();
            Snowball s = w.spawn(mz.clone().add(dir.clone().multiply(0.3)), Snowball.class, sb -> {
                sb.setItem(new ItemStack(Material.BLACK_CONCRETE));
                sb.setShooter(p);
                sb.setGravity(true);
                sb.setVelocity(dir.clone().multiply(projSpeed));
                sb.getPersistentDataContainer().set(K_PROJ, PersistentDataType.BYTE, (byte) 1);
            });
            balls.add(s);

            w.spawnParticle(Particle.FLAME, mz, 15, 0.1, 0.1, 0.1, 0.08);
            w.spawnParticle(Particle.LARGE_SMOKE, mz, 12, 0.15, 0.15, 0.15, 0.03);
            w.spawnParticle(Particle.EXPLOSION, mz, 1);
            w.playSound(mz, Sound.ENTITY_GENERIC_EXPLODE, 2f, 0.6f);
            w.playSound(mz, Sound.ENTITY_BLAZE_SHOOT, 1.5f, 0.5f);
        }
    }
}
