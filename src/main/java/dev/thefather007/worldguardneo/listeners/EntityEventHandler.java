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

    /* ---------------- Mob spawning ---------------- */

    @SubscribeEvent
    public void onMobSpawn(FinalizeSpawnEvent e) {
        if (!(e.getLevel() instanceof ServerLevel sl)) return;
        if (!mod.isProtectionActive(sl)) return;
        RegionManager mgr = mod.regions().get(sl);
        String entityId = null;  // lazily resolved — many spawns don't need it

        // World-config blocked-entities: cancel spawn entirely for listed entity types.
        // Applied per-world; covers both vanilla and modded mobs by ResourceLocation.
        // Accepts both forms in config: "minecraft:zombie" OR plain "zombie" (for vanilla
        // entities only — modded entities must use the full namespaced id).
        try {
            var ws = mod.config().worldOrGlobal(sl);
            if (ws != null && ws.blockedEntities != null && !ws.blockedEntities.isEmpty()) {
                // getKey can return null for entities without a registry mapping (some modded
                // mobs). Guard it: a null key here previously threw an NPE that the surrounding
                // catch swallowed, silently skipping the hostile-mob suppressor below too.
                var key = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                        .getKey(e.getEntity().getType());
                if (key != null) {
                    entityId = key.toString();
                    String action = ws.blockedEntities.get(entityId);
                    if (action == null && entityId.startsWith("minecraft:")) {
                        // Try the bare form too — admins often write just "zombie" in config.
                        action = ws.blockedEntities.get(entityId.substring("minecraft:".length()));
                    }
                    if ("deny".equalsIgnoreCase(action)) {
                        e.setSpawnCancelled(true);
                        return;
                    }
                }
            }
            // Global hostile-mob suppressor: any monster gets cancelled if config wants it.
            // Useful for peaceful-mode subworlds without disabling difficulty globally. Runs
            // independently of the registry-key lookup above so a keyless modded mob can't
            // slip past it.
            if (mod.config().global().blockedEntityHostile
                    && e.getEntity() instanceof net.minecraft.world.entity.monster.Monster) {
                e.setSpawnCancelled(true);
                return;
            }
        } catch (Throwable ignored) {}

        double x = e.getX(), y = e.getY(), z = e.getZ();
        // Share the applicable list across both flag checks — one spatial lookup, not two.
        var applicable = mgr.getApplicable(x, y, z);
        if (!mgr.testState(Flags.MOB_SPAWNING, applicable, null)) {
            e.setSpawnCancelled(true);
            return;
        }
        var denied = mgr.resolveValue(Flags.DENY_SPAWN, applicable, null);
        if (denied != null && !denied.isEmpty()) {
            try {
                // Reuse entityId if we already resolved it above.
                if (entityId == null) {
                    var key = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                            .getKey(e.getEntity().getType());
                    if (key == null) return; // unregistered entity — can't match the deny-spawn set
                    entityId = key.toString();
                }
                if (denied.contains(entityId)) {
                    e.setSpawnCancelled(true);
                } else if (entityId.startsWith("minecraft:")
                        && denied.contains(entityId.substring("minecraft:".length()))) {
                    // Support short form "zombie" as well as full "minecraft:zombie" in the
                    // deny-spawn set. substring() only allocates when the full form didn't
                    // match — saves a string per spawn in the typical "full id matches" case.
                    e.setSpawnCancelled(true);
                }
            } catch (Throwable t) {
                WorldGuardNeo.LOGGER.debug("DENY_SPAWN match failed", t);
            }
        }
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
        // Single spatial-index lookup reused across all three state-flag tests below.
        var applicable = mgr.getApplicable(x, y, z);
        UUID actor = attacker.getUUID();

        // Order matters: HangingEntity and ArmorStand are checked FIRST, before the generic
        // LivingEntity branch. ArmorStand is technically a LivingEntity in vanilla but
        // behaves as decoration (cannot move, cannot attack) — routing it through MOB_DAMAGE
        // would conflate "kill an aggressive mob" with "vandalize decoration". Treat both
        // hanging entities and armor stands the same way: BUILD + BLOCK_BREAK both required.
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
            // World-wide protectVehicles: globally protect vehicles regardless of region flags.
            // Useful for survival servers where griefing minecart networks is a problem.
            // bypass was already checked at the top of this method, so no need to re-test here.
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

    /* ---------------- Right-click EXACTLY on an entity (armor stand armor swap) ---------------- */

    /**
     * Armor stands swap their gear through {@code Entity#interactAt}, which in the interaction
     * pipeline runs right AFTER {@link PlayerInteractEvent.EntityInteractSpecific} — NOT after
     * the regular {@code EntityInteract} that {@link #onEntityInteract} hooks. So taking/placing
     * armor on a stand never reaches our EntityInteract handler and slips through. We must cancel
     * at EntityInteractSpecific: per NeoForge's pipeline, a server-side cancel here prevents
     * {@code interactAt}, which is exactly the armor swap. Without this, a non-member can strip a
     * claimed stand's diamond armor even though they can't break the stand itself.
     *
     * <p>Gated by the same build-access membership check as everything else.
     */
    @SubscribeEvent
    public void onEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific e) {
        if (e.getLevel().isClientSide()) return;
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        if (mod.perms().has(p, "worldguardneo.region.bypass")) return;
        Entity target = e.getTarget();
        // Limit to decoration we protect; armor stands are the key case, item frames already
        // go through EntityInteract but cancelling here too is harmless and closes any gap.
        if (!(target instanceof net.minecraft.world.entity.decoration.ArmorStand)
                && !(target instanceof net.minecraft.world.entity.decoration.HangingEntity)) {
            return;
        }
        if (!mod.isProtectionActive(p.serverLevel())) return;
        RegionManager mgr = mod.regions().get(p.serverLevel());
        double x = target.getX(), y = target.getY(), z = target.getZ();
        UUID actor = p.getUUID();
        if (!mgr.testBuildAccess(Flags.INTERACT, x, y, z, actor)
                || !mgr.testBuildAccess(Flags.BUILD, x, y, z, actor)) {
            e.setCanceled(true);
            p.displayClientMessage(
                    net.minecraft.network.chat.Component.literal(mod.i18n().raw("msg.interact.decoration-denied")), true);
        }
    }

    /* ---------------- Right-click on entity (rotate item frame, swap armor stand gear, etc) ---------------- */

    /**
     * Right-click interactions with decoration entities — item frames (place/rotate/remove
     * an item), armor stands (swap armor). These don't fire {@link AttackEntityEvent},
     * they fire {@code PlayerInteractEvent.EntityInteract} instead. Without this hook a
     * non-owner could pop an item out of someone's claimed item frame just by right-clicking.
     *
     * <p>Gated under the same flags as left-click ({@code BUILD} + {@code INTERACT}). We
     * use INTERACT here because it's logically a "use" action — adding an item to a frame
     * is interaction, not breaking. BUILD doubles as the owner/member gate because most
     * regions deny build to outsiders by default.
     *
     * <p>Mobs, players, vehicles fall through — those interactions (trading, mounting,
     * riding) are gated by their own flags or vanilla rules.
     */
    @SubscribeEvent
    public void onEntityInteract(PlayerInteractEvent.EntityInteract e) {
        if (e.getLevel().isClientSide()) return;
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        if (mod.perms().has(p, "worldguardneo.region.bypass")) return;
        Entity target = e.getTarget();
        if (!(target instanceof net.minecraft.world.entity.decoration.HangingEntity)
                && !(target instanceof net.minecraft.world.entity.decoration.ArmorStand)) {
            return; // Not a decoration — let vanilla / other mods handle.
        }
        if (!mod.isProtectionActive(p.serverLevel())) return;
        RegionManager mgr = mod.regions().get(p.serverLevel());
        double x = target.getX(), y = target.getY(), z = target.getZ();
        UUID actor = p.getUUID();
        // INTERACT and BUILD both must allow — same gate as left-click but uses INTERACT
        // (right-click is logically interaction, not destruction). Membership protection via
        // testBuildAccess: owners/members pass, strangers are blocked when flags are unset.
        if (!mgr.testBuildAccess(Flags.INTERACT, x, y, z, actor)
                || !mgr.testBuildAccess(Flags.BUILD, x, y, z, actor)) {
            e.setCanceled(true);
            p.displayClientMessage(
                    net.minecraft.network.chat.Component.literal(mod.i18n().raw("msg.interact.decoration-denied")), true);
        }
    }

    /* ---------------- Projectile hits decoration ---------------- */

    /**
     * Stops arrows / snowballs / tridents / shulker bullets etc. from breaking item frames,
     * paintings, and armor stands in regions where BUILD is denied. These projectile hits
     * do NOT route through {@link AttackEntityEvent}, so without this handler a hostile
     * player could destroy decorations from afar even when melee is blocked.
     *
     * <p>We mirror the left-click semantics: BUILD + BLOCK_BREAK must both allow. The shooter
     * (when available) is used as the actor for group-resolution; for dispenser-fired or
     * source-less projectiles we pass null and fall back to the global default.
     *
     * <p>Cancelling {@code ProjectileImpactEvent} makes the projectile pass through the entity
     * without damage. The arrow keeps flying — which is exactly what we want (visual fairness).
     */
    @SubscribeEvent
    public void onProjectileImpactEntity(ProjectileImpactEvent e) {
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
        if (e.getSource().getEntity() instanceof ServerPlayer attacker && attacker != victim
                && !(victim instanceof net.minecraft.world.entity.decoration.ArmorStand)) {
            // Armor stands are decoration: their BUILD/BLOCK_BREAK gating lives in the
            // melee/projectile handlers, not under mob-damage.
            if (!mod.perms().has(attacker, "worldguardneo.region.bypass")) {
                var applicableAtVictim = mgr.getApplicable(victim.getX(), victim.getY(), victim.getZ());
                StateFlag gate = victim instanceof Player ? Flags.PVP : Flags.MOB_DAMAGE;
                if (!mgr.testState(gate, applicableAtVictim, attacker.getUUID())) {
                    e.setCanceled(true);
                    attacker.displayClientMessage(
                            net.minecraft.network.chat.Component.literal(mod.i18n().raw(
                                    victim instanceof Player ? "msg.attack.pvp-denied"
                                                             : "msg.attack.mob-denied")), true);
                    return;
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
        // Single applicable lookup reused across all damage-type tests below.
        var applicable = mgr.getApplicable(x, y, z);
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
