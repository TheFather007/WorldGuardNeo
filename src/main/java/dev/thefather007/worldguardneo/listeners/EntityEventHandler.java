package dev.thefather007.worldguardneo.listeners;

import dev.thefather007.worldguardneo.WorldGuardNeo;
import dev.thefather007.worldguardneo.flags.Flags;
import dev.thefather007.worldguardneo.flags.StateFlag;
import dev.thefather007.worldguardneo.region.RegionManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;

import java.util.UUID;

/**
 * Entity-side protections: mob spawning, PVP, mob damage, fall/fire/drown damage,
 * vehicle destruction.
 */
public final class EntityEventHandler {

    private final WorldGuardNeo mod;
    public EntityEventHandler(WorldGuardNeo mod) { this.mod = mod; }

    /* ---------------- Mob block griefing ---------------- */

    /**
     * Gate vanilla mob griefing (Enderman block pickup, sheep eating grass, zombies breaking
     * doors, …) by the per-region {@code mob-grief} flag, without touching the world-wide
     * {@code mobGriefing} game rule. Default ALLOW — admins opt in with {@code mob-grief deny}.
     */
    @SubscribeEvent
    public void onMobGriefing(net.neoforged.neoforge.event.entity.EntityMobGriefingEvent e) {
        Entity entity = e.getEntity();
        if (entity == null) return;
        if (!(entity.level() instanceof ServerLevel sl)) return;
        if (!mod.isProtectionActive(sl)) return;
        RegionManager mgr = mod.regions().get(sl);
        if (!mgr.testState(Flags.MOB_GRIEF, null, entity.getX(), entity.getY(), entity.getZ())) {
            e.setCanGrief(false);
        }
    }

    /* ---------------- Mob spawning ---------------- */

    @SubscribeEvent
    public void onMobSpawn(FinalizeSpawnEvent e) {
        if (!(e.getLevel() instanceof ServerLevel sl)) return;
        if (!mod.isProtectionActive(sl)) return;
        RegionManager mgr = mod.regions().get(sl);
        String entityId = null;  // lazily resolved — many spawns don't need it

        // World-config blocked-entities: cancel spawn for listed types (per-world, vanilla + modded
        // by ResourceLocation). Accepts "minecraft:zombie" or bare "zombie" (vanilla only).
        try {
            var ws = mod.config().worldOrGlobal(sl);
            if (ws != null && ws.blockedEntities != null && !ws.blockedEntities.isEmpty()) {
                // getKey is null for keyless modded mobs — guard it; an NPE here used to be
                // swallowed by the catch, silently skipping the hostile-mob suppressor below too.
                var key = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                        .getKey(e.getEntity().getType());
                if (key != null) {
                    entityId = key.toString();
                    String action = ws.blockedEntities.get(entityId);
                    if (action == null && entityId.startsWith("minecraft:")) {
                        action = ws.blockedEntities.get(entityId.substring("minecraft:".length()));
                    }
                    if ("deny".equalsIgnoreCase(action)) {
                        e.setSpawnCancelled(true);
                        return;
                    }
                }
            }
            // Global hostile-mob suppressor: cancel any Monster when config wants it. Runs
            // independently of the registry-key lookup so a keyless modded mob can't slip past.
            if (mod.config().global().blockedEntityHostile
                    && e.getEntity() instanceof net.minecraft.world.entity.monster.Monster) {
                e.setSpawnCancelled(true);
                return;
            }
        } catch (Throwable ignored) {}

        double x = e.getX(), y = e.getY(), z = e.getZ();
        // Share the applicable list across both flag checks — one spatial lookup.
        var applicable = mgr.getApplicable(x, y, z);
        if (!mgr.testState(Flags.MOB_SPAWNING, applicable, null)) {
            e.setSpawnCancelled(true);
            return;
        }
        var denied = mgr.resolveValue(Flags.DENY_SPAWN, applicable, null);
        if (denied != null && !denied.isEmpty()) {
            try {
                if (entityId == null) {
                    var key = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                            .getKey(e.getEntity().getType());
                    if (key == null) return; // unregistered entity — can't match the deny-spawn set
                    entityId = key.toString();
                }
                if (denied.contains(entityId)) {
                    e.setSpawnCancelled(true);
                    return;
                } else if (entityId.startsWith("minecraft:")
                        && denied.contains(entityId.substring("minecraft:".length()))) {
                    // Bare short form ("zombie"); substring only allocates when the full id missed.
                    e.setSpawnCancelled(true);
                    return;
                }
            } catch (Throwable t) {
                WorldGuardNeo.LOGGER.debug("DENY_SPAWN match failed", t);
            }
        }

        // Per-type spawn caps (spawn-limit): for each region declaring a cap matching this type,
        // count live entities of that type in its bounds and cancel once the cap is reached. Uses
        // each region's OWN value (not inherited); the count scan only runs when a cap matches.
        try {
            for (int i = 0, n = applicable.size(); i < n; i++) {
                var reg = applicable.get(i);
                var caps = reg.getFlag(Flags.SPAWN_LIMIT);
                if (caps == null || caps.isEmpty()) continue;
                if (entityId == null) {
                    var key = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                            .getKey(e.getEntity().getType());
                    if (key == null) break; // unregistered type — can't match a cap
                    entityId = key.toString();
                }
                int max = matchSpawnCap(caps, entityId);
                if (max < 0) continue;
                if (countOfTypeIn(sl, reg, e.getEntity().getType()) >= max) {
                    e.setSpawnCancelled(true);
                    return;
                }
            }
        } catch (Throwable t) {
            WorldGuardNeo.LOGGER.debug("SPAWN_LIMIT check failed", t);
        }
    }

    /**
     * Parse a {@code spawn-limit} set for a cap on {@code entityId} ("type:max" entries, full or
     * short {@code minecraft:} form). Returns the max, or -1 if no entry matches this type.
     */
    private static int matchSpawnCap(java.util.Set<String> caps, String entityId) {
        String shortId = entityId.startsWith("minecraft:") ? entityId.substring("minecraft:".length()) : null;
        for (String entry : caps) {
            int colon = entry.lastIndexOf(':');
            if (colon <= 0 || colon == entry.length() - 1) continue; // malformed "type:max"
            String type = entry.substring(0, colon);
            if (type.equals(entityId) || (shortId != null && type.equals(shortId))) {
                try { return Math.max(0, Integer.parseInt(entry.substring(colon + 1).trim())); }
                catch (NumberFormatException ignored) { return -1; }
            }
        }
        return -1;
    }

    /** Count live entities of {@code type} whose position lies within {@code reg}'s bounding box. */
    private static int countOfTypeIn(ServerLevel sl,
                                     dev.thefather007.worldguardneo.region.ProtectedRegion reg,
                                     net.minecraft.world.entity.EntityType<?> type) {
        var mn = reg.minimumBound();
        var mx = reg.maximumBound();
        net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(
                mn.x(), mn.y(), mn.z(), mx.x() + 1.0, mx.y() + 1.0, mx.z() + 1.0);
        return sl.getEntitiesOfClass(Entity.class, box, ent -> ent.getType() == type).size();
    }

    /* ---------------- Player-attacks-entity (covers PVP, mob-damage, vehicle-destroy) ---------------- */

    @SubscribeEvent
    public void onPlayerAttack(AttackEntityEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer attacker)) return;
        if (mod.perms().has(attacker, "worldguardneo.region.bypass")) return;
        Entity target = e.getTarget();
        if (target == null) return;
        ServerLevel lvl = attacker.serverLevel();
        if (!mod.isProtectionActive(lvl)) return;
        RegionManager mgr = mod.regions().get(lvl);
        double x = target.getX(), y = target.getY(), z = target.getZ();
        // Single spatial lookup reused across the three state-flag tests below.
        var applicable = mgr.getApplicable(x, y, z);
        UUID actor = attacker.getUUID();

        // Decoration (HangingEntity/ArmorStand) checked FIRST. ArmorStand is a LivingEntity in
        // vanilla but is decoration, so routing it through MOB_DAMAGE would conflate "kill a mob"
        // with "vandalize decoration". Both gate on BUILD + BLOCK_BREAK instead.
        boolean isDecoration = target instanceof net.minecraft.world.entity.decoration.HangingEntity
                            || target instanceof net.minecraft.world.entity.decoration.ArmorStand;
        if (isDecoration) {
            if (!mgr.testBuildAccess(Flags.BUILD, x, y, z, actor)
                    || !mgr.testBuildAccess(Flags.BLOCK_BREAK, x, y, z, actor)) {
                e.setCanceled(true);
                attacker.displayClientMessage(
                        net.minecraft.network.chat.Component.literal(mod.i18n().raw("msg.attack.build-denied")), true);
            }
            return;
        }

        if (target instanceof Player) {
            if (!mgr.testState(Flags.PVP, applicable, actor)) {
                e.setCanceled(true);
                // Show attacker WHY their attack didn't land — silent cancel feels broken.
                attacker.displayClientMessage(
                        net.minecraft.network.chat.Component.literal(mod.i18n().raw("msg.attack.pvp-denied")), true);
                return;
            }
        } else if (target instanceof LivingEntity) {
            if (!mgr.testState(Flags.MOB_DAMAGE, applicable, actor)) {
                e.setCanceled(true);
                attacker.displayClientMessage(
                        net.minecraft.network.chat.Component.literal(mod.i18n().raw("msg.attack.mob-denied")), true);
                return;
            }
        }

        if (target instanceof AbstractMinecart || target instanceof Boat) {
            // World-wide protectVehicles: protect vehicles regardless of region flags. bypass was
            // already checked at the top of this method.
            var ws = mod.config().worldOrGlobal(lvl);
            if (ws != null && ws.protectVehicles) {
                e.setCanceled(true);
                attacker.displayClientMessage(
                        net.minecraft.network.chat.Component.literal(mod.i18n().raw("msg.attack.vehicle-denied")), true);
                return;
            }
            if (!mgr.testState(Flags.VEHICLE_DESTROY, applicable, actor)) {
                e.setCanceled(true);
                attacker.displayClientMessage(
                        net.minecraft.network.chat.Component.literal(mod.i18n().raw("msg.attack.vehicle-denied")), true);
            }
        }
    }

    /* ---------------- Vehicle mounting (boats / minecarts) ---------------- */

    /**
     * Gate a player boarding a boat/minecart by the per-region {@code vehicle-enter} flag
     * (default ALLOW). Only player→vehicle mounts; mob mounts and dismounts pass through.
     */
    @SubscribeEvent
    public void onEntityMount(net.neoforged.neoforge.event.entity.EntityMountEvent e) {
        if (!e.isMounting()) return;
        if (!(e.getEntityMounting() instanceof ServerPlayer p)) return;
        Entity vehicle = e.getEntityBeingMounted();
        if (!(vehicle instanceof AbstractMinecart) && !(vehicle instanceof Boat)) return;
        if (e.getLevel().isClientSide()) return;
        if (!mod.isProtectionActive(e.getLevel())) return;
        RegionManager mgr = mod.regions().get(p.serverLevel());
        // Resolve the flag first; only consult region.bypass when it denies (lazy-bypass pattern).
        if (!mgr.testState(Flags.VEHICLE_ENTER, p.getUUID(), vehicle.getX(), vehicle.getY(), vehicle.getZ())
                && !mod.perms().has(p, "worldguardneo.region.bypass")) {
            e.setCanceled(true);
            p.displayClientMessage(
                    net.minecraft.network.chat.Component.literal(mod.i18n().raw("msg.vehicle.enter-denied")), true);
        }
    }

    /* ---------------- Right-click EXACTLY on an entity (armor stand armor swap) ---------------- */

    /**
     * Armor stands swap gear via {@code Entity#interactAt}, which runs after
     * {@link PlayerInteractEvent.EntityInteractSpecific} — NOT after the regular EntityInteract
     * that {@link #onEntityInteract} hooks, so armor swaps slip past that handler. Cancelling
     * here prevents {@code interactAt} (the swap). Gated by the same build-access check.
     */
    @SubscribeEvent
    public void onEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific e) {
        if (e.getLevel().isClientSide()) return;
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        if (mod.perms().has(p, "worldguardneo.region.bypass")) return;
        Entity target = e.getTarget();
        // Limit to protected decoration; armor stands are the key case.
        if (!(target instanceof net.minecraft.world.entity.decoration.ArmorStand)
                && !(target instanceof net.minecraft.world.entity.decoration.HangingEntity)) {
            return;
        }
        if (!mod.isProtectionActive(p.serverLevel())) return;
        RegionManager mgr = mod.regions().get(p.serverLevel());
        double x = target.getX(), y = target.getY(), z = target.getZ();
        UUID actor = p.getUUID();
        // Dedicated armor-stand-use toggle (default ALLOW): an explicit deny blocks even members,
        // layered on top of the build-access gate below.
        if (target instanceof net.minecraft.world.entity.decoration.ArmorStand
                && !mgr.testState(Flags.ARMOR_STAND_USE, actor, x, y, z)) {
            e.setCanceled(true);
            p.displayClientMessage(
                    net.minecraft.network.chat.Component.literal(mod.i18n().raw("msg.interact.decoration-denied")), true);
            return;
        }
        if (!mgr.testBuildAccess(Flags.INTERACT, x, y, z, actor)
                || !mgr.testBuildAccess(Flags.BUILD, x, y, z, actor)) {
            e.setCanceled(true);
            p.displayClientMessage(
                    net.minecraft.network.chat.Component.literal(mod.i18n().raw("msg.interact.decoration-denied")), true);
        }
    }

    /* ---------------- Right-click on entity (rotate item frame, swap armor stand gear, etc) ---------------- */

    /**
     * Right-click on decoration — item frames (place/rotate/remove) and armor stands (swap armor).
     * These fire {@code EntityInteract}, not {@link AttackEntityEvent}; without this a non-owner
     * could pop an item out of a claimed frame. Gated on BUILD + INTERACT (right-click is logically
     * a "use"; BUILD doubles as the member gate). Mobs/players/vehicles fall through to their own
     * flags or vanilla rules.
     */
    @SubscribeEvent
    public void onEntityInteract(PlayerInteractEvent.EntityInteract e) {
        if (e.getLevel().isClientSide()) return;
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        if (mod.perms().has(p, "worldguardneo.region.bypass")) return;
        Entity target = e.getTarget();
        if (!(target instanceof net.minecraft.world.entity.decoration.HangingEntity)
                && !(target instanceof net.minecraft.world.entity.decoration.ArmorStand)) {
            return; // not a decoration — let vanilla / other mods handle
        }
        if (!mod.isProtectionActive(p.serverLevel())) return;
        RegionManager mgr = mod.regions().get(p.serverLevel());
        double x = target.getX(), y = target.getY(), z = target.getZ();
        UUID actor = p.getUUID();
        // Dedicated decoration toggles (default ALLOW): an explicit deny blocks even members.
        // item-frame-rotate only applies to a frame that already holds an item (rotating);
        // placing/removing still falls under the build-access gate below.
        if (target instanceof net.minecraft.world.entity.decoration.ItemFrame frame
                && !frame.getItem().isEmpty()
                && !mgr.testState(Flags.ITEM_FRAME_ROTATE, actor, x, y, z)) {
            e.setCanceled(true);
            p.displayClientMessage(
                    net.minecraft.network.chat.Component.literal(mod.i18n().raw("msg.interact.decoration-denied")), true);
            return;
        }
        if (target instanceof net.minecraft.world.entity.decoration.ArmorStand
                && !mgr.testState(Flags.ARMOR_STAND_USE, actor, x, y, z)) {
            e.setCanceled(true);
            p.displayClientMessage(
                    net.minecraft.network.chat.Component.literal(mod.i18n().raw("msg.interact.decoration-denied")), true);
            return;
        }
        // INTERACT + BUILD must both allow (members pass, strangers blocked when flags unset).
        if (!mgr.testBuildAccess(Flags.INTERACT, x, y, z, actor)
                || !mgr.testBuildAccess(Flags.BUILD, x, y, z, actor)) {
            e.setCanceled(true);
            p.displayClientMessage(
                    net.minecraft.network.chat.Component.literal(mod.i18n().raw("msg.interact.decoration-denied")), true);
        }
    }

    /* ---------------- Projectile hits decoration ---------------- */

    /**
     * Stops projectiles (arrows, snowballs, tridents, …) from breaking decoration where BUILD is
     * denied; these don't route through {@link AttackEntityEvent}, so otherwise a hostile player
     * could destroy decorations from afar even when melee is blocked. Mirrors left-click: BUILD +
     * BLOCK_BREAK must both allow. Shooter (if any) is the actor; source-less projectiles pass null.
     * Cancelling makes the projectile pass through without damage while still flying on.
     */
    @SubscribeEvent
    public void onProjectileImpactEntity(ProjectileImpactEvent e) {
        if (e.isCanceled()) return; // already handled/cancelled upstream — skip redundant work
        var hit = e.getRayTraceResult();
        if (!(hit instanceof net.minecraft.world.phys.EntityHitResult ehr)) return;
        Entity target = ehr.getEntity();
        boolean isDecoration = target instanceof net.minecraft.world.entity.decoration.HangingEntity
                            || target instanceof net.minecraft.world.entity.decoration.ArmorStand;
        // Vehicles (boats, minecarts) are NOT LivingEntities, so projectile hits on them never
        // reach LivingIncomingDamageEvent — without gating here, a single arrow pops a boat in
        // a vehicle-destroy=DENY region (or with protectVehicles on) even though melee is blocked.
        boolean isVehicle = target instanceof AbstractMinecart || target instanceof Boat;
        if (!isDecoration && !isVehicle) {
            return; // Mobs/players take the LivingIncomingDamageEvent path; rest is vanilla.
        }
        Level lvl = target.level();
        if (lvl.isClientSide()) return;
        if (!mod.isProtectionActive(lvl)) return;

        // Identify the shooter (if any) so owner/member resolution can match group filters.
        // A shooter with bypass walks through; null-shooter (e.g. dispenser) falls back to
        // global flag defaults.
        UUID shooterId = null;
        Entity owner = e.getProjectile().getOwner();
        if (owner instanceof ServerPlayer sp) {
            if (mod.perms().has(sp, "worldguardneo.region.bypass")) return;
            shooterId = sp.getUUID();
        }

        RegionManager mgr = mod.regions().get(lvl);
        double x = target.getX(), y = target.getY(), z = target.getZ();

        if (isVehicle) {
            // Mirror the melee vehicle branch in onPlayerAttack: world-wide protectVehicles
            // first, then the per-region vehicle-destroy flag.
            var ws = mod.config().worldOrGlobal(lvl);
            if (ws != null && ws.protectVehicles) {
                e.setCanceled(true);
                return;
            }
            if (!mgr.testState(Flags.VEHICLE_DESTROY, shooterId, x, y, z)) {
                e.setCanceled(true);
            }
            return;
        }

        if (!mgr.testBuildAccess(Flags.BUILD, x, y, z, shooterId)
                || !mgr.testBuildAccess(Flags.BLOCK_BREAK, x, y, z, shooterId)) {
            // Pass-through: arrow continues flying, doesn't damage the decoration.
            e.setCanceled(true);
        }
    }

    /* ---------------- Generic damage handling (environmental) ---------------- */

    @SubscribeEvent
    public void onLivingDamage(LivingIncomingDamageEvent e) {
        LivingEntity victim = e.getEntity();
        Level levelObj = victim.level();
        if (levelObj.isClientSide()) return;
        if (!mod.isProtectionActive(levelObj)) return;

        // Cache the manager once for use across world-config and per-region paths below.
        RegionManager mgr = mod.regions().get(levelObj);

        // ---- player-sourced damage: melee AND ranged (arrows, tridents, potions, …) ----
        // DamageSource#getEntity resolves to the CAUSING entity — the shooter for projectiles —
        // so this closes the classic bypass where AttackEntityEvent gates melee but a bow shot
        // sails through pvp=deny / mob-damage=deny untouched. Melee passes through both this
        // and AttackEntityEvent with the same verdict, which is harmless.
        // Victim's applicable regions, resolved at most once and shared between the player-attack
        // gate and the environmental-damage cascade below (both at the victim's position). Lazy:
        // the common mob-environmental-damage path returns before it's needed and never pays for it.
        java.util.List<dev.thefather007.worldguardneo.region.ProtectedRegion> applicable = null;

        if (e.getSource().getEntity() instanceof ServerPlayer attacker && attacker != victim
                && !(victim instanceof net.minecraft.world.entity.decoration.ArmorStand)) {
            // Armor stands are decoration: their BUILD/BLOCK_BREAK gating lives in the
            // melee/projectile handlers, not under mob-damage.
            if (!mod.perms().has(attacker, "worldguardneo.region.bypass")) {
                applicable = mgr.getApplicable(victim.getX(), victim.getY(), victim.getZ());
                StateFlag gate = victim instanceof Player ? Flags.PVP : Flags.MOB_DAMAGE;
                if (!mgr.testState(gate, applicable, attacker.getUUID())) {
                    // Public API override hook (see RegionFlagDeniedEvent) — a listener may permit.
                    boolean overridden = !applicable.isEmpty()
                            && dev.thefather007.worldguardneo.api.events.RegionFlagDeniedEvent.isOverridden(
                                    applicable.get(0), gate, attacker, victim instanceof Player ? "pvp" : "mob-damage");
                    if (!overridden) {
                        e.setCanceled(true);
                        attacker.displayClientMessage(
                                net.minecraft.network.chat.Component.literal(mod.i18n().raw(
                                        victim instanceof Player ? "msg.attack.pvp-denied"
                                                                 : "msg.attack.mob-denied")), true);
                        return;
                    }
                }
            }
        }

        if (!(victim instanceof ServerPlayer sp)) return;

        // World-wide config: invincibleRegions forces all-region invincibility, preventMobDamage
        // blocks ALL mob-sourced damage across the world (regardless of regions).
        if (levelObj instanceof ServerLevel slvl) {
            var ws = mod.config().worldOrGlobal(slvl);
            if (ws != null) {
                if (ws.preventMobDamage && e.getSource().getEntity() instanceof LivingEntity
                        && !(e.getSource().getEntity() instanceof Player)) {
                    e.setCanceled(true);
                    return;
                }
                // invincibleRegions: if the player is inside ANY region, take no damage.
                // Cheap O(1) bucket lookup via the spatial index, no allocations.
                if (ws.invincibleRegions
                        && mgr.hasAnyAt(victim.getX(), victim.getY(), victim.getZ())) {
                    e.setCanceled(true); return;
                }
            }
        }

        double x = victim.getX(), y = victim.getY(), z = victim.getZ();
        // Reuse the list already resolved by the player-attack gate above (same position) when
        // present; otherwise resolve it once here for the environmental-damage cascade below.
        if (applicable == null) applicable = mgr.getApplicable(x, y, z);
        UUID id = sp.getUUID();

        // Wilderness fast path: if there are no applicable regions AND the global region has
        // no damage-related flags set, all testState calls below would return the flag default
        // (allow) and we'd do nothing. Skip the cascade entirely. Most of the world is
        // wilderness with vanilla damage rules, so this short-circuit fires often.
        if (applicable.isEmpty()) {
            // Probe just one common damage-related flag on global — if unset, none of the
            // others matter for cancellation (defaults allow damage). Cheap shortcut.
            var globalReg = mgr.globalRegion();
            if (globalReg.getFlag(Flags.INVINCIBLE) == null
                    && globalReg.getFlag(Flags.FALL_DAMAGE) == null
                    && globalReg.getFlag(Flags.FIRE_DAMAGE) == null
                    && globalReg.getFlag(Flags.DROWN_DAMAGE) == null
                    && globalReg.getFlag(Flags.SUFFOCATION_DAMAGE) == null
                    && globalReg.getFlag(Flags.PLAYER_DAMAGE) == null) {
                return;
            }
        }

        if (mgr.testState(Flags.INVINCIBLE, applicable, id)) {
            // Full cancellation skips knockback/cooldowns too, matching player expectation.
            e.setCanceled(true);
            return;
        }

        DamageSource src = e.getSource();
        // Typed damage-key references — robust to localization and mods adding new sources.
        if (src.is(DamageTypes.FALL)) {
            if (!mgr.testState(Flags.FALL_DAMAGE, applicable, id)) e.setCanceled(true);
        } else if (src.is(DamageTypes.IN_FIRE)
                || src.is(DamageTypes.ON_FIRE)
                || src.is(DamageTypes.LAVA)
                || src.is(DamageTypes.HOT_FLOOR)) {
            if (!mgr.testState(Flags.FIRE_DAMAGE, applicable, id)) e.setCanceled(true);
        } else if (src.is(DamageTypes.DROWN)) {
            if (!mgr.testState(Flags.DROWN_DAMAGE, applicable, id)) e.setCanceled(true);
        } else if (src.is(DamageTypes.IN_WALL)) {
            if (!mgr.testState(Flags.SUFFOCATION_DAMAGE, applicable, id)) e.setCanceled(true);
        } else if (!(src.getEntity() instanceof Player)) {
            // Environmental / non-player source falls under generic player-damage.
            if (!mgr.testState(Flags.PLAYER_DAMAGE, applicable, id)) e.setCanceled(true);
        }
    }
}
