package dev.thefather007.worldguardneo.listeners;

import dev.thefather007.worldguardneo.WorldGuardNeo;
import dev.thefather007.worldguardneo.flags.Flags;
import dev.thefather007.worldguardneo.flags.StateFlag;
import dev.thefather007.worldguardneo.region.ProtectedRegion;
import dev.thefather007.worldguardneo.region.RegionManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Player-side protections: region entry/exit announcements, blocked commands,
 * game-mode lock, heal / feed flags, time- and weather-locks, chat guards,
 * item pickup / drop guards.
 *
 * Per-player tick state is kept in tiny POD objects keyed by UUID so that
 * iteration during ticking is allocation-light.
 */
public final class PlayerEventHandler {

    /** Stable id for our transient MAX_SPEED attribute modifier (session-only; never persisted). */
    private static final net.minecraft.resources.ResourceLocation SPEED_MODIFIER_ID =
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("worldguardneo", "max_speed");

    private final WorldGuardNeo mod;
    public PlayerEventHandler(WorldGuardNeo mod) { this.mod = mod; }

    /** Snapshot per player kept between ticks. */
    private static final class PlayerState {
        Set<String> lastRegions = Set.of();
        int     healCooldown    = 0;
        int     feedCooldown    = 0;
        double  lastSafeX, lastSafeY, lastSafeZ;
        boolean lastSafeValid   = false;
        long    lastTimePacket  = 0;
        boolean timeLockActive  = false;
        boolean weatherLockActive = false;
        /** True while our transient MAX_SPEED modifier is applied — for the dormancy guard. */
        boolean speedModified   = false;
        /**
         * The player's game mode BEFORE a region's {@code game-mode} flag overrode it. Null =
         * we never changed it. Restored when the player leaves the region(s) imposing it, so a
         * {@code game-mode adventure} zone doesn't permanently trap the player in that mode.
         */
        GameType origGameMode   = null;
        /**
         * Snapshot of the player's position at the moment of death. Used by the respawn
         * handler to look up region-scoped {@code spawn} flags from the death point, not
         * from the post-respawn position (which is already at vanilla bed/world-spawn).
         * NaN = no death recorded since last respawn.
         */
        double  deathX = Double.NaN, deathY = Double.NaN, deathZ = Double.NaN;
        net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> deathDim = null;
        /**
         * Keep-inventory / keep-xp decision snapshotted at the moment of death. The clone
         * handler reads THESE instead of re-resolving the flag — otherwise an admin toggling
         * the flag while the player is on the death screen could cause items to be neither
         * dropped (LivingDropsEvent already cancelled) nor restored (clone re-resolve denies),
         * losing them entirely. Snapshotting makes death-drop handling atomic.
         */
        boolean deathKeepInv = false;
        boolean deathKeepXp  = false;
        /**
         * Tick-flag relevance cache. {@code tickFlagsRelevant} answers "does any region in the
         * current applicable chain (incl. parents) or the global region set ANY flag values?".
         * When false, the whole per-tick flag cascade (entry/exit tests, game-mode, heal, feed,
         * hunger, speed, time/weather — a dozen resolutions) is provably a no-op and is skipped.
         * Recomputed when the region set changes or {@link ProtectedRegion#flagEpoch()} moves
         * (any flag/parent/priority edit anywhere), so admin changes apply on the next tick.
         */
        long    flagEpochSeen    = -1L;
        boolean tickFlagsRelevant = true;
    }

    private final Map<UUID, PlayerState> states = new HashMap<>();

    /* ---------------- Per-player tick ---------------- */

    @SubscribeEvent
    public void onPlayerTick(PlayerTickEvent.Post e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        ServerLevel lvl = p.serverLevel();
        // World-level kill-switch: when regions are disabled for this world (useRegions=false),
        // none of the per-tick protections (entry/exit, heal/feed, game-mode, speed, time/weather)
        // should run — matches the block/entity handlers that already honour this flag.
        if (!mod.isProtectionActive(lvl)) return;
        RegionManager mgr = mod.regions().get(lvl);
        // mgr is never null — get(Level) uses computeIfAbsent.

        double x = p.getX(), y = p.getY(), z = p.getZ();

        // Cheapest possible fast path: no spatial-index entry under us AND we have never
        // entered a region (no state yet). Skips all allocations including PlayerState itself.
        // This is the case for ~99 % of player-ticks on a wilderness-heavy server.
        UUID id = p.getUUID();
        PlayerState st = states.get(id);
        List<ProtectedRegion> here = mgr.getApplicable(x, y, z);
        if (here.isEmpty() && st == null) {
            return;
        }
        if (st == null) st = states.computeIfAbsent(id, k -> new PlayerState());

        // Mildly fast path: had history but is now in wilderness — still need to fire farewells.
        if (here.isEmpty() && st.lastRegions.isEmpty()) {
            st.lastSafeX = x; st.lastSafeY = y; st.lastSafeZ = z; st.lastSafeValid = true;
            return;
        }

        // Hot-path optimisation: if the applicable list matches lastRegions exactly (same
        // ids, same count) then no transitions happened this tick — skip the HashSet
        // allocation and all entry/exit logic. This is the common case once a player
        // settles inside a region.
        boolean unchanged = here.size() == st.lastRegions.size();
        if (unchanged) {
            for (int i = 0, n = here.size(); i < n; i++) {
                if (!st.lastRegions.contains(here.get(i).id())) { unchanged = false; break; }
            }
        }

        // Steady-state shortcut: most regions are membership-only claims with no flag values at
        // all. For a player standing inside one, every per-tick resolution below (entry/exit,
        // game-mode, heal, feed, hunger, speed, time/weather — ~12 flag walks) provably yields
        // the defaults. Cache "any flags anywhere in the chain?" per player and skip the whole
        // cascade while it stays false. Invalidation: region-set change (transition) or the
        // global flag epoch moving (any flag/parent/priority edit on any region).
        long epoch = ProtectedRegion.flagEpoch();
        if (!unchanged || st.flagEpochSeen != epoch) {
            st.flagEpochSeen = epoch;
            st.tickFlagsRelevant = anyFlagsInChain(here, mgr);
        }
        if (unchanged && !st.tickFlagsRelevant
                // Live overrides must be unwound before we may go dormant: a previously applied
                // time/weather lock, speed override, or game-mode override still needs its restore
                // path below to run until it has reset.
                && !st.timeLockActive && !st.weatherLockActive
                && !st.speedModified
                && st.origGameMode == null) {
            st.lastSafeX = x; st.lastSafeY = y; st.lastSafeZ = z; st.lastSafeValid = true;
            return;
        }

        Set<String> current;
        if (unchanged) {
            // No greeting/farewell work needed. Still update lastSafe and check live flags
            // (entry/exit guards already ran last tick when state changed).
            current = st.lastRegions;
        } else if (here.isEmpty()) {
            current = Set.of();
        } else if (here.size() == 1) {
            // Single-region case is so common we hand-roll a Set.of(id) to skip the HashSet.
            current = Set.of(here.get(0).id());
        } else {
            current = new HashSet<>(here.size() * 2);
            for (int i = 0, n = here.size(); i < n; i++) current.add(here.get(i).id());
        }

        // IMPORTANT: do NOT call canBypass() unconditionally here. onPlayerTick runs 20×/sec,
        // and canBypass() queries the permission backend for region.bypass. With LuckPerms
        // verbose on, that produced a permission check EVERY tick → console spam (and needless
        // work). bypass only matters when an ENTRY/EXIT flag would actually deny passage, which
        // is rare (both default to allow). So we resolve it lazily: the first time a deny is hit
        // we compute it once and cache it for the rest of this tick.
        // bypassState: 0 = not yet computed, 1 = true, 2 = false.
        int[] bypassState = {0};

        // Single ENTRY test for the whole tick: it considers all applicable regions
        // with priority + group resolution.
        // Plain entry guard — only consult bypass if ENTRY would deny.
        if (!mgr.testState(Flags.ENTRY, here, id) && !resolveBypassCached(p, bypassState)) {
            String denyMsg = null;
            for (int i = 0, n = here.size(); i < n; i++) {
                if (!st.lastRegions.contains(here.get(i).id())) {
                    String m = here.get(i).getFlag(Flags.ENTRY_DENY_MESSAGE);
                    if (m != null) { denyMsg = m; break; }
                }
            }
            if (denyMsg != null) p.displayClientMessage(Component.literal(denyMsg), true);
            bounceFromEntry(p, st, lvl, mgr, id);
            return;
        }
        // Entry-vehicle: a stricter guard that only triggers when the player is mounted
        // on something (boat, minecart, mob, etc.). Useful for spawn regions that should
        // allow walking in but not riding. Again, bypass is only consulted on a deny.
        if (p.isPassenger() && !mgr.testState(Flags.ENTRY_VEHICLE, here, id)
                && !resolveBypassCached(p, bypassState)) {
            bounceFromEntry(p, st, lvl, mgr, id);
            return;
        }

        // entry: greetings + notify-enter for newly-entered regions
        // Skip entirely when the region set is unchanged — there are no newly-entered
        // regions to greet. The heal/feed/time/weather flag handling below still runs.
        if (!unchanged) for (int i = 0, n = here.size(); i < n; i++) {
            ProtectedRegion r = here.get(i);
            if (st.lastRegions.contains(r.id())) continue;
            // Public API event — fired BEFORE greeting so listeners can suppress it
            // by reading flags themselves. Posted on the server thread synchronously.
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(
                    new dev.thefather007.worldguardneo.api.events.RegionEnterEvent(p, r));
            runRegionCommand(p, r, r.getFlag(Flags.ON_ENTRY)); // on-entry command flag
            if (mod.config().global().announceGreetings) {
                String g = r.getFlag(Flags.GREETING);
                if (g != null) p.displayClientMessage(Component.literal(g), false);
                String gt = r.getFlag(Flags.GREETING_TITLE);
                if (gt != null) sendTitle(p, gt);
            }
            Boolean notifyEnter = r.getFlag(Flags.NOTIFY_ENTER);
            if (Boolean.TRUE.equals(notifyEnter)) {
                mod.worldEvents().broadcastNotification(p, r.id(), true);
            }
            // Action-bar region-info: shown to the player who entered. Combines the region
            // name with a PvP-status indicator so players know if they're in a fight zone.
            // The PvP state is computed via the existing flag resolution (with parents).
            if (mod.config().global().announceRegionActionBar) {
                StateFlag.State pvpHere = resolveStateWithParents(r, Flags.PVP);
                boolean pvpAllow = pvpHere == StateFlag.State.ALLOW
                        || (pvpHere == null && Flags.PVP.defaultAllow());
                String pvpTag = mod.i18n().raw(pvpAllow ? "msg.region.pvp-on" : "msg.region.pvp-off");
                String bar = mod.i18n().format("msg.region.enter-actionbar",
                        "region", r.id(), "pvp", pvpTag);
                p.displayClientMessage(Component.literal(bar), true);
            }
        }

        // exit: farewells + exit-deny test.
        //
        // EXIT semantics: a region the player just left (was in lastRegions, not in current)
        // is checked individually. We walk that region's parents up to 32 hops looking for
        // an EXIT flag value. RegionGroup matching is done against the leaving region.
        // We can't use RegionManager.testState because the player's current position is
        // already outside the region, so resolution there would always return the default.
        if (!unchanged && !st.lastRegions.isEmpty()) {
            String denyMsg = null;
            boolean denyExit = false;
            // Resolve the EXIT/EXIT_VEHICLE flags FIRST, without touching the permission backend.
            // Only if a region actually denies exit do we then consult bypass. This is what keeps
            // region.bypass out of the permission log on a normal walk-out of an allow-exit region
            // (the default) — we never query it unless an EXIT=deny is genuinely in effect.
            boolean passenger = p.isPassenger();
            for (String oldId : st.lastRegions) {
                if (current.contains(oldId)) continue;
                var or = mgr.get(oldId).orElse(null);
                if (or == null) continue;
                // resolveStateForRegion walks the leaving region's parents AND applies the SOURCE
                // region's group filter — so an inherited group-scoped EXIT deny is honoured
                // correctly (the old code read the child's group for an inherited flag).
                StateFlag.State resolved = mgr.resolveStateForRegion(Flags.EXIT, or, id);
                if (resolved == StateFlag.State.DENY) {
                    denyExit = true;
                    String m = or.getFlag(Flags.EXIT_DENY_MESSAGE);
                    if (m != null) { denyMsg = m; break; }
                }
                // Stricter exit-vehicle: only triggers if the player is mounted.
                if (passenger) {
                    StateFlag.State rv = mgr.resolveStateForRegion(Flags.EXIT_VEHICLE, or, id);
                    if (rv == StateFlag.State.DENY) {
                        denyExit = true;
                        if (denyMsg == null) {
                            String m = or.getFlag(Flags.EXIT_DENY_MESSAGE);
                            if (m != null) denyMsg = m;
                        }
                    }
                }
            }
            // A deny is in effect — NOW check bypass (this is the only path that queries it).
            if (denyExit && !resolveBypassCached(p, bypassState)) {
                if (denyMsg != null) p.displayClientMessage(Component.literal(denyMsg), true);
                bounce(p, st, lvl);
                return;
            }
            for (String oldId : st.lastRegions) {
                if (current.contains(oldId)) continue;
                mgr.get(oldId).ifPresent(r -> {
                    // Public API event — fired BEFORE farewell so listeners can suppress
                    // the message by reading flags themselves.
                    net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(
                            new dev.thefather007.worldguardneo.api.events.RegionLeaveEvent(p, r));
                    runRegionCommand(p, r, r.getFlag(Flags.ON_EXIT)); // on-exit command flag
                    if (mod.config().global().announceFarewells) {
                        String f = r.getFlag(Flags.FAREWELL);
                        if (f != null) p.displayClientMessage(Component.literal(f), false);
                        String ft = r.getFlag(Flags.FAREWELL_TITLE);
                        if (ft != null) sendTitle(p, ft);
                    }
                    Boolean notifyLeave = r.getFlag(Flags.NOTIFY_LEAVE);
                    if (Boolean.TRUE.equals(notifyLeave)) {
                        mod.worldEvents().broadcastNotification(p, r.id(), false);
                    }
                    // Action-bar: tell the player they left this specific region. We don't
                    // include a PvP tag because they may be entering a NEW region this same
                    // tick — the entry block above will show the new region's PvP status.
                    // If they're stepping into wilderness, "leave" is the only relevant signal.
                    if (mod.config().global().announceRegionActionBar && here.isEmpty()) {
                        p.displayClientMessage(Component.literal(
                                mod.i18n().format("msg.region.leave-actionbar",
                                        "region", r.id())), true);
                    }
                });
            }
        }

        st.lastRegions = current;
        st.lastSafeX = x; st.lastSafeY = y; st.lastSafeZ = z; st.lastSafeValid = true;

        // From here on we resolve several flags at the same point. Reuse the already-computed
        // applicable list to avoid 9 redundant spatial-index lookups + 9 list allocations.
        // When the player is in wilderness, ALL of these flags fall back to the global region,
        // so we only skip the work when there's no global override either — checked implicitly
        // since global flag values for these are typically unset.
        List<ProtectedRegion> applicable = here;

        // game-mode lock. Snapshot the player's pre-region mode on first override and restore it
        // on exit — mirrors the MAX_SPEED snapshot/restore below — so a `game-mode` region doesn't
        // permanently trap the player in the imposed mode after they walk out (WorldGuard restores).
        String gm = mgr.resolveValue(Flags.GAME_MODE, applicable, id);
        if (gm != null) {
            GameType wanted = GameType.byName(gm.toLowerCase(Locale.ROOT), null);
            if (wanted != null) {
                GameType cur = p.gameMode.getGameModeForPlayer();
                if (cur != wanted) {
                    if (st.origGameMode == null) st.origGameMode = cur; // capture once, before changing
                    p.setGameMode(wanted);
                }
            }
        } else if (st.origGameMode != null) {
            // No game-mode flag applies here anymore — restore the snapshot, unless the player
            // already happens to be in that mode (or changed it themselves to match).
            if (p.gameMode.getGameModeForPlayer() != st.origGameMode) {
                p.setGameMode(st.origGameMode);
            }
            st.origGameMode = null;
        }

        // heal flag
        Integer healDelay  = mgr.resolveValue(Flags.HEAL_DELAY,  applicable, id);
        Integer healAmount = mgr.resolveValue(Flags.HEAL_AMOUNT, applicable, id);
        if (healDelay != null && healAmount != null && healDelay > 0) {
            st.healCooldown++;
            if (st.healCooldown >= healDelay * 20) {
                st.healCooldown = 0;
                Integer max = mgr.resolveValue(Flags.MAX_HEAL, applicable, id);
                float cap = max != null ? max.floatValue() : p.getMaxHealth();
                Integer min = mgr.resolveValue(Flags.MIN_HEAL, applicable, id);
                float floor = min != null ? min.floatValue() : 0f;
                float hp = p.getHealth();
                int amount = healAmount.intValue();
                if (amount > 0) {
                    // Heal UP toward the max. min-hp is the lower bound of the band, NOT a
                    // precondition: a player below it (badly hurt) is exactly who should be
                    // healed, so we must not gate on hp >= min (the previous behaviour, which
                    // refused to heal anyone who had dropped below the floor).
                    if (hp < cap) {
                        float delta = Math.min((float) amount, cap - hp);
                        if (delta > 0) p.heal(delta);
                    }
                } else if (amount < 0) {
                    // Negative heal-amount drains health (a "damage zone"), but never below
                    // the min-hp floor.
                    if (hp > floor) {
                        float delta = Math.min((float) -amount, hp - floor);
                        if (delta > 0) {
                            try { p.hurt(p.damageSources().generic(), delta); }
                            catch (Throwable t) { WorldGuardNeo.LOGGER.debug("heal-drain failed", t); }
                        }
                    }
                }
            }
        }

        // feed flag
        Integer feedDelay  = mgr.resolveValue(Flags.FEED_DELAY,  applicable, id);
        Integer feedAmount = mgr.resolveValue(Flags.FEED_AMOUNT, applicable, id);
        if (feedDelay != null && feedAmount != null && feedDelay > 0) {
            st.feedCooldown++;
            if (st.feedCooldown >= feedDelay * 20) {
                st.feedCooldown = 0;
                Integer max = mgr.resolveValue(Flags.FEED_MAX, applicable, id);
                Integer min = mgr.resolveValue(Flags.FEED_MIN, applicable, id);
                int cap = max != null ? max : 20;
                var food = p.getFoodData();
                int now = food.getFoodLevel();
                // FEED_MIN: do nothing while hunger is above this floor.
                if ((min == null || now < min) && now < cap) {
                    food.setFoodLevel(Math.min(cap, now + feedAmount));
                }
            }
        }

        // hunger-drain: when DENY, zero out food exhaustion accumulated this tick so the
        // hunger bar never depletes. We can't intercept the exhaustion event directly, so we
        // observe and reset. This is best-effort: exhaustion that has already converted to
        // saturation/hunger this tick will linger one frame.
        if (!mgr.testState(Flags.HUNGER_DRAIN, applicable, id)) {
            var food = p.getFoodData();
            // Setting exhaustion to 0 prevents the next decrement. The Mojang field is private
            // so we use the public setter which uses a delta.  setExhaustion(-current) brings
            // it back to 0; positive values would compound, so this is safe.
            try {
                float ex = food.getExhaustionLevel();
                if (ex > 0f) food.setExhaustion(0f);
            } catch (Throwable ignored) {}
        }

        // max-speed: apply a SESSION-ONLY (transient) attribute modifier instead of overwriting the
        // base value. Transient modifiers are never persisted, so logging out inside a max-speed
        // region can no longer bake the modified speed into the player's saved data — the old
        // setBaseValue approach did, and because the restore snapshot was discarded on logout the
        // player came back permanently slowed. This is also stateless and self-correcting: our
        // modifier is present iff a max-speed flag currently applies, and the true base value is
        // never touched, so removal always returns the player to their real speed.
        Double speed = mgr.resolveValue(Flags.MAX_SPEED, applicable, id);
        try {
            var attr = p.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
            if (attr != null) {
                if (speed != null) {
                    // ADD_VALUE amount chosen so (base + amount) == the target speed, matching the
                    // old base-override semantics (sprint/effect multipliers still stack on top).
                    double amount = speed - attr.getBaseValue();
                    var existing = attr.getModifier(SPEED_MODIFIER_ID);
                    if (existing == null || Math.abs(existing.amount() - amount) > 1e-9) {
                        attr.removeModifier(SPEED_MODIFIER_ID);
                        attr.addOrUpdateTransientModifier(
                                new net.minecraft.world.entity.ai.attributes.AttributeModifier(
                                        SPEED_MODIFIER_ID, amount,
                                        net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE));
                    }
                    st.speedModified = true;
                } else if (st.speedModified || attr.getModifier(SPEED_MODIFIER_ID) != null) {
                    // No max-speed applies here anymore — drop our modifier (base was never touched).
                    attr.removeModifier(SPEED_MODIFIER_ID);
                    st.speedModified = false;
                }
            }
        } catch (Throwable ignored) {}

        // glide (elytra): when denied, force-stop fall-flying so a player can't soar across the
        // region. stopFallFlying clears the gliding flag; the player simply falls/walks. Only
        // touched while actually gliding, so it costs nothing for grounded players. Bypass holders
        // are exempt — dropping an admin out of the air mid-flight (possible fall death) would be a
        // nasty surprise, and bypass means "ignore region protection". Bypass is resolved lazily
        // (only when glide actually denies), reusing the per-tick cache.
        if (p.isFallFlying() && !mgr.testState(Flags.GLIDE, applicable, id)
                && !resolveBypassCached(p, bypassState)) {
            p.stopFallFlying();
        }

        // per-player time / weather lock — sent every 40 ticks (2 s) while active,
        // and a one-shot restore packet when the player leaves a locked region.
        if ((lvl.getGameTime() - st.lastTimePacket) >= 40) {
            String tl = mgr.resolveValue(Flags.TIME_LOCK, applicable, id);
            String wl = mgr.resolveValue(Flags.WEATHER_LOCK, applicable, id);
            boolean tNow = tl != null, wNow = wl != null;
            if (tNow || wNow) {
                applyTimeWeatherPacket(p, lvl, tl, wl);
                st.timeLockActive    = tNow;
                st.weatherLockActive = wNow;
                st.lastTimePacket    = lvl.getGameTime();
            } else if (st.timeLockActive || st.weatherLockActive) {
                // Restore world-true time and weather to the client.
                restoreTimeWeatherPacket(p, lvl, st.timeLockActive, st.weatherLockActive);
                st.timeLockActive = st.weatherLockActive = false;
                st.lastTimePacket = lvl.getGameTime();
            }
        }
    }

    private boolean canBypass(ServerPlayer p) {
        return mod.perms().has(p, "worldguardneo.region.bypass");
    }

    /**
     * Run a region's {@code on-entry}/{@code on-exit} command from the server console (elevated),
     * substituting {@code %player%}, {@code %region%}, {@code %world%}. No-op for null/blank. The
     * flag is settable only via its per-flag permission, so only admins can attach a command.
     * Best-effort: a bad command never breaks the tick.
     */
    private void runRegionCommand(ServerPlayer p, ProtectedRegion r, String cmd) {
        if (cmd == null || cmd.isBlank()) return;
        try {
            String c = cmd.trim();
            if (c.startsWith("/")) c = c.substring(1);
            c = c.replace("%player%", p.getGameProfile().getName())
                 .replace("%region%", r.id())
                 .replace("%world%",  p.serverLevel().dimension().location().toString());
            var server = p.getServer();
            if (server == null) return;
            // Console source positioned at the player, silent (no command feedback spam).
            var source = server.createCommandSourceStack()
                    .withPosition(p.position())
                    .withLevel(p.serverLevel())
                    .withSuppressedOutput();
            server.getCommands().performPrefixedCommand(source, c);
        } catch (Throwable t) {
            WorldGuardNeo.LOGGER.debug("region command '{}' for {} failed", cmd, r.id(), t);
        }
    }

    /**
     * Lazily resolves {@link #canBypass} at most once per tick, caching the result in a small
     * int holder (0 = not yet computed, 1 = true, 2 = false). This is what keeps onPlayerTick
     * from querying the permission backend for region.bypass on every single tick — the check
     * only happens the first time an ENTRY/EXIT deny is actually encountered, which is rare.
     * Without this, LuckPerms verbose logging spammed the console once per tick per player.
     */
    private boolean resolveBypassCached(ServerPlayer p, int[] holder) {
        if (holder[0] == 0) {
            holder[0] = canBypass(p) ? 1 : 2;
        }
        return holder[0] == 1;
    }

    /**
     * True if any region in the applicable list (walking parent chains, bounded) or the global
     * region sets at least one flag value. When false, every flag resolution at this position
     * returns its default — callers can skip whole resolution cascades.
     */
    private static boolean anyFlagsInChain(List<ProtectedRegion> here, RegionManager mgr) {
        if (mgr.globalRegion().hasFlags()) return true;
        for (int i = 0, n = here.size(); i < n; i++) {
            ProtectedRegion cur = here.get(i);
            for (int hops = 0; cur != null && hops < 32; hops++) {
                if (cur.hasFlags()) return true;
                cur = cur.parent();
            }
        }
        return false;
    }

    /** Walk parent chain looking for a state-flag value. Bounded at 32 hops. */
    private static StateFlag.State resolveStateWithParents(ProtectedRegion r, StateFlag flag) {
        ProtectedRegion cursor = r;
        for (int hops = 0; cursor != null && hops < 32; hops++) {
            StateFlag.State v = cursor.getFlag(flag);
            if (v != null) return v;
            cursor = cursor.parent();
        }
        return null;
    }

    /** Display a title via the dedicated client packet, fading in/out with sensible defaults. */
    private static void sendTitle(ServerPlayer p, String text) {
        try {
            Component c = Component.literal(text);
            p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket(c));
        } catch (Throwable t) {
            WorldGuardNeo.LOGGER.debug("sendTitle failed", t);
        }
    }

    /** Send the player back to their last safe position (or world spawn fallback). */
    private void bounce(ServerPlayer p, PlayerState st, ServerLevel lvl) {
        if (st.lastSafeValid) {
            p.teleportTo(st.lastSafeX, st.lastSafeY, st.lastSafeZ);
        } else {
            BlockPos spawn = lvl.getSharedSpawnPos();
            p.teleportTo(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5);
        }
    }

    /**
     * Bounce a player who is being denied ENTRY. Unlike {@link #bounce} (used for EXIT, where
     * sending the player back INSIDE the region is the intended behaviour), an entry-denied player
     * must end up OUTSIDE. If their last "safe" position is itself entry-denied — which happens when
     * ENTRY is toggled to deny while the player is already inside, or a region is created/redefined
     * around them — teleporting there would trap them in a per-tick teleport loop (the next tick
     * re-denies and re-bounces to the same spot). Detect that case and fall back to world spawn,
     * invalidating the poisoned safe position so it isn't reused until a fresh safe tick records one.
     */
    private void bounceFromEntry(ServerPlayer p, PlayerState st, ServerLevel lvl,
                                 RegionManager mgr, UUID id) {
        if (st.lastSafeValid
                && mgr.testState(Flags.ENTRY, id, st.lastSafeX, st.lastSafeY, st.lastSafeZ)) {
            p.teleportTo(st.lastSafeX, st.lastSafeY, st.lastSafeZ);
        } else {
            BlockPos spawn = lvl.getSharedSpawnPos();
            p.teleportTo(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5);
            st.lastSafeValid = false;
        }
    }

    /**
     * Best-effort per-player time/weather override via client packets.
     * Stays per-player (uses the connection's PacketSender) so the world clock isn't touched.
     */
    private void applyTimeWeatherPacket(ServerPlayer p, ServerLevel lvl, String timeLock, String weatherLock) {
        try {
            long override = lvl.getDayTime();
            if (timeLock != null) {
                switch (timeLock.toLowerCase(Locale.ROOT)) {
                    case "day"      -> override = 6000;
                    case "night"    -> override = 18000;
                    case "noon"     -> override = 6000;
                    case "midnight" -> override = 18000;
                    case "sunset"   -> override = 12000;
                    case "sunrise"  -> override = 23000;
                    default -> {
                        try { override = Long.parseLong(timeLock.trim()); } catch (NumberFormatException ignored) {}
                    }
                }
            }
            p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTimePacket(
                    lvl.getGameTime(), override, false));

            if (weatherLock != null) {
                boolean raining = weatherLock.equalsIgnoreCase("rain") || weatherLock.equalsIgnoreCase("thunder");
                boolean thunder = weatherLock.equalsIgnoreCase("thunder");
                p.connection.send(new net.minecraft.network.protocol.game.ClientboundGameEventPacket(
                        raining
                                ? net.minecraft.network.protocol.game.ClientboundGameEventPacket.START_RAINING
                                : net.minecraft.network.protocol.game.ClientboundGameEventPacket.STOP_RAINING,
                        0f));
                // Always set rain & thunder levels explicitly. Without these, transitioning
                // from "rain" → "clear" leaves the client with a non-zero rain ramp that
                // ramps back up over a few seconds.
                p.connection.send(new net.minecraft.network.protocol.game.ClientboundGameEventPacket(
                        net.minecraft.network.protocol.game.ClientboundGameEventPacket.RAIN_LEVEL_CHANGE,
                        raining ? 1f : 0f));
                p.connection.send(new net.minecraft.network.protocol.game.ClientboundGameEventPacket(
                        net.minecraft.network.protocol.game.ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE,
                        thunder ? 1f : 0f));
            }
        } catch (Throwable t) {
            WorldGuardNeo.LOGGER.debug("time/weather packet failed", t);
        }
    }

    /** One-shot restore of true world time/weather to a player. */
    private void restoreTimeWeatherPacket(ServerPlayer p, ServerLevel lvl, boolean wasTime, boolean wasWeather) {
        try {
            if (wasTime) {
                p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTimePacket(
                        lvl.getGameTime(), lvl.getDayTime(), lvl.getGameRules().getBoolean(
                                net.minecraft.world.level.GameRules.RULE_DAYLIGHT)));
            }
            if (wasWeather) {
                boolean raining = lvl.isRaining();
                p.connection.send(new net.minecraft.network.protocol.game.ClientboundGameEventPacket(
                        raining
                                ? net.minecraft.network.protocol.game.ClientboundGameEventPacket.START_RAINING
                                : net.minecraft.network.protocol.game.ClientboundGameEventPacket.STOP_RAINING,
                        0f));
                p.connection.send(new net.minecraft.network.protocol.game.ClientboundGameEventPacket(
                        net.minecraft.network.protocol.game.ClientboundGameEventPacket.RAIN_LEVEL_CHANGE,
                        lvl.getRainLevel(1f)));
                p.connection.send(new net.minecraft.network.protocol.game.ClientboundGameEventPacket(
                        net.minecraft.network.protocol.game.ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE,
                        lvl.getThunderLevel(1f)));
            }
        } catch (Throwable t) {
            WorldGuardNeo.LOGGER.debug("time/weather restore failed", t);
        }
    }

    /* ---------------- Blocked commands ---------------- */

    @SubscribeEvent
    public void onCommand(CommandEvent e) {
        var src = e.getParseResults().getContext().getSource();
        if (!(src.getEntity() instanceof ServerPlayer p)) return;
        if (!mod.isProtectionActive(p.serverLevel())) return;
        if (canBypass(p)) return;
        RegionManager mgr = mod.regions().get(p.serverLevel());
        double x = p.getX(), y = p.getY(), z = p.getZ();

        String full = e.getParseResults().getReader().getString();
        if (full.isEmpty()) return;
        // Extract the head literal (between optional '/' and the first whitespace).
        int from = full.charAt(0) == '/' ? 1 : 0;
        int end  = from;
        for (int len = full.length(); end < len; end++) {
            if (Character.isWhitespace(full.charAt(end))) break;
        }
        if (end <= from) return;
        String head = full.substring(from, end).toLowerCase(Locale.ROOT);

        // /execute compound resolution: if the literal head is "execute", scan for the
        // last "run" subcommand and use whatever follows as the EFFECTIVE head. Without
        // this, a player blocked from /kick could still kick via /execute as @a run kick X.
        // We check the LAST occurrence because nested executes are legal:
        //   /execute as @a run execute as @s run kick X
        if ("execute".equals(head)) {
            String real = extractExecuteRealHead(full, end);
            if (real != null) head = real;
        }

        UUID uid = p.getUUID();
        Set<String> allowed = mgr.resolveValue(Flags.ALLOWED_CMDS, x, y, z, uid);
        if (allowed != null && !allowed.isEmpty() && !containsCmd(allowed, head)) {
            e.setCanceled(true);
            p.displayClientMessage(Component.literal(mod.i18n().raw("msg.protection.cmd-blocked")), true);
            return;
        }
        Set<String> blocked = mgr.resolveValue(Flags.BLOCKED_CMDS, x, y, z, uid);
        if (blocked != null && containsCmd(blocked, head)) {
            e.setCanceled(true);
            p.displayClientMessage(Component.literal(mod.i18n().raw("msg.protection.cmd-blocked")), true);
        }
    }

    /**
     * Scan an /execute command line for the LAST {@code run <cmd>} segment and return the
     * lowercased name of {@code <cmd>}. Returns null if no {@code run} keyword is found
     * (e.g. {@code /execute as @a if score ...} without a final run — those don't actually
     * execute a sub-command and can be left to vanilla parsing).
     *
     * <p>Walks token-by-token from {@code startIdx}, tracking the position immediately after
     * the most recent {@code run} keyword. The scan is O(N) over the input, no allocations
     * until we return the substring (which only happens when a run-target is found).
     */
    private static String extractExecuteRealHead(String full, int startIdx) {
        int len = full.length();
        int runTargetStart = -1;
        int i = startIdx;
        while (i < len) {
            // skip whitespace
            while (i < len && Character.isWhitespace(full.charAt(i))) i++;
            if (i >= len) break;
            int tokStart = i;
            while (i < len && !Character.isWhitespace(full.charAt(i))) i++;
            int tokEnd = i;
            // Is this token "run" (lowercase)? We compare without allocating.
            if (tokEnd - tokStart == 3
                    && (full.charAt(tokStart)     | 0x20) == 'r'
                    && (full.charAt(tokStart + 1) | 0x20) == 'u'
                    && (full.charAt(tokStart + 2) | 0x20) == 'n') {
                // Remember: the next non-whitespace token after THIS "run" is the real head.
                // We keep updating runTargetStart so nested executes pick the innermost.
                runTargetStart = i;
            }
        }
        if (runTargetStart < 0) return null;
        // Skip whitespace, read next word.
        while (runTargetStart < len && Character.isWhitespace(full.charAt(runTargetStart))) {
            runTargetStart++;
        }
        int realEnd = runTargetStart;
        while (realEnd < len && !Character.isWhitespace(full.charAt(realEnd))) realEnd++;
        if (realEnd <= runTargetStart) return null;
        return full.substring(runTargetStart, realEnd).toLowerCase(Locale.ROOT);
    }

    private static boolean containsCmd(Set<String> set, String head) {
        // O(n) iteration is acceptable here: a blocked-cmds set is rarely larger than a
        // few dozen entries and we want case-insensitive matching while tolerating an
        // optional leading slash on either side. Use regionMatches() to avoid the substring
        // allocation when the entry has a leading "/" — checking ~20 entries per command
        // shouldn't allocate a string each time.
        int headLen = head.length();
        for (String entry : set) {
            int off = entry.startsWith("/") ? 1 : 0;
            if (entry.length() - off == headLen
                    && entry.regionMatches(true, off, head, 0, headLen)) {
                return true;
            }
        }
        return false;
    }

    /* ---------------- Chat ---------------- */

    @SubscribeEvent
    public void onChat(ServerChatEvent e) {
        ServerPlayer p = e.getPlayer();
        if (p == null) return;
        if (!mod.isProtectionActive(p.serverLevel())) return;
        // Admins with bypass can chat anywhere — silencing them by accident in a no-chat
        // PvP arena is annoying and they'd just dimension-hop to talk.
        if (canBypass(p)) return;
        RegionManager mgr = mod.regions().get(p.serverLevel());
        double x = p.getX(), y = p.getY(), z = p.getZ();
        if (!mgr.testState(Flags.SEND_CHAT, p.getUUID(), x, y, z)) {
            e.setCanceled(true);
            p.displayClientMessage(Component.literal(mod.i18n().raw("msg.protection.chat-blocked")), true);
        }
    }

    /* ---------------- Item pickup / drop ---------------- */

    /**
     * Last game-time we showed the pickup-denied action bar, per player. The pickup event
     * re-fires every tick while the player stands over a denied item, so an unthrottled
     * message would flicker continuously.
     */
    private final Map<UUID, Long> lastPickupDenyMsg = new HashMap<>();

    @SubscribeEvent
    public void onItemPickup(ItemEntityPickupEvent.Pre e) {
        if (!(e.getPlayer() instanceof ServerPlayer p)) return;
        if (!mod.isProtectionActive(p.serverLevel())) return;
        RegionManager mgr = mod.regions().get(p.serverLevel());
        // Spatial test first: if the flag wouldn't deny anyway, skip the permission check.
        // testState returns true (allow) for positions outside all regions, so this short-
        // circuits at near-zero cost for wilderness pickups (the common case).
        if (mgr.testState(Flags.ITEM_PICKUP, p.getUUID(), p.getX(), p.getY(), p.getZ())) return;
        if (canBypass(p)) return;
        e.setCanPickup(TriState.FALSE);
        long now = p.serverLevel().getGameTime();
        Long last = lastPickupDenyMsg.get(p.getUUID());
        if (last == null || now - last >= 60) {
            lastPickupDenyMsg.put(p.getUUID(), now);
            p.displayClientMessage(
                    Component.literal(mod.i18n().raw("msg.protection.deny-pickup")), true);
        }
    }

    @SubscribeEvent
    public void onItemToss(ItemTossEvent e) {
        if (!(e.getPlayer() instanceof ServerPlayer p)) return;
        if (!mod.isProtectionActive(p.serverLevel())) return;
        RegionManager mgr = mod.regions().get(p.serverLevel());
        if (mgr.testState(Flags.ITEM_DROP, p.getUUID(), p.getX(), p.getY(), p.getZ())) return;
        if (canBypass(p)) return;
        e.setCanceled(true);
        // CRITICAL: ItemTossEvent fires AFTER the stack has already been removed from the
        // inventory and wrapped into the spawned ItemEntity. Cancelling alone discards that
        // entity, so the denied drop silently DELETED the items. Put the stack back (the slot
        // it came from has just been freed) and tell the player why nothing happened.
        var stack = e.getEntity().getItem();
        if (!stack.isEmpty()) {
            if (!p.getInventory().add(stack)) {
                // Inventory unexpectedly full (another mod raced the freed slot): never
                // destroy items — letting the drop proceed is the lesser evil.
                e.setCanceled(false);
                return;
            }
            p.containerMenu.broadcastChanges();
        }
        p.displayClientMessage(
                Component.literal(mod.i18n().raw("msg.protection.deny-drop")), true);
    }

    /* ---------------- Sleep, ender pearl, chorus fruit, hunger drain, max speed, spawn ---------------- */

    @SubscribeEvent
    public void onSleep(net.neoforged.neoforge.event.entity.player.CanPlayerSleepEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        if (!mod.isProtectionActive(p.serverLevel())) return;
        if (canBypass(p)) return;
        var bedPos = e.getPos();
        if (bedPos == null) return;
        RegionManager mgr = mod.regions().get(p.serverLevel());
        if (!mgr.testState(Flags.SLEEP, p.getUUID(), bedPos.getX(), bedPos.getY(), bedPos.getZ())) {
            // Force a "not_safe" outcome so the player gets the standard "you may not rest" feedback.
            e.setProblem(net.minecraft.world.entity.player.Player.BedSleepingProblem.NOT_SAFE);
        }
    }

    /**
     * Hunger drain: when the food-exhaustion calculation is about to occur, zero it out if the
     * region disallows hunger drain. NeoForge doesn't expose a dedicated exhaustion event, so
     * we use the per-tick post hook to reset extra exhaustion accumulated since last tick.
     * Implemented inside the existing onPlayerTick body — see the food handling there.
     */

    /**
     * Block ender-pearl teleport landings into regions that forbid them. Catches the post-throw
     * arrival via the entity's per-tick lookup. We can't cancel mid-flight cleanly, so we react
     * on the destination tick.
     */
    /**
     * Cancel ender-pearl teleport that would land in a forbidden region. For PLAYER teleports
     * we use {@code ender-build} (the existing flag for player-driven ender movement). For
     * MOB teleports (Endermen, Shulkers throwing themselves) we use the {@code mob-teleport}
     * flag — separate so admins can disable mob teleports without affecting players.
     */
    @SubscribeEvent
    public void onEnderPearlTeleport(net.neoforged.neoforge.event.entity.EntityTeleportEvent.EnderPearl e) {
        var entity = e.getEntity();
        if (entity instanceof ServerPlayer p) {
            if (!mod.isProtectionActive(p.serverLevel())) return;
            if (canBypass(p)) return;
            RegionManager mgr = mod.regions().get(p.serverLevel());
            if (!mgr.testState(Flags.ENDER_BUILD, p.getUUID(), e.getTargetX(), e.getTargetY(), e.getTargetZ())) {
                e.setCanceled(true);
            }
        } else if (entity != null && entity.level() instanceof ServerLevel sl) {
            if (!mod.isProtectionActive(sl)) return;
            // Non-player teleporter (rare for EnderPearl, but mod-driven sources exist).
            RegionManager mgr = mod.regions().get(sl);
            if (!mgr.testState(Flags.MOB_TELEPORT, null, e.getTargetX(), e.getTargetY(), e.getTargetZ())) {
                e.setCanceled(true);
            }
        }
    }

    /**
     * Block chorus-fruit teleport into forbidden regions. Player path uses {@code chorus-fruit};
     * mob path (Shulkers eat chorus when forced via /effect or mods) uses {@code mob-teleport}.
     */
    @SubscribeEvent
    public void onChorusFruitTeleport(net.neoforged.neoforge.event.entity.EntityTeleportEvent.ChorusFruit e) {
        var entity = e.getEntity();
        if (entity instanceof ServerPlayer p) {
            if (!mod.isProtectionActive(p.serverLevel())) return;
            if (canBypass(p)) return;
            RegionManager mgr = mod.regions().get(p.serverLevel());
            if (!mgr.testState(Flags.CHORUS_FRUIT, p.getUUID(), e.getTargetX(), e.getTargetY(), e.getTargetZ())) {
                e.setCanceled(true);
            }
        } else if (entity != null && entity.level() instanceof ServerLevel sl) {
            if (!mod.isProtectionActive(sl)) return;
            RegionManager mgr = mod.regions().get(sl);
            if (!mgr.testState(Flags.MOB_TELEPORT, null, e.getTargetX(), e.getTargetY(), e.getTargetZ())) {
                e.setCanceled(true);
            }
        }
    }

    /**
     * Generic mob teleport — Endermen / Shulker natural teleport. We target the
     * {@code EnderEntity} subclass specifically rather than the base {@code EntityTeleportEvent}
     * because (a) it's the exact event for natural mob teleports and (b) targeting the base
     * event risks a compile/runtime issue if the base isn't {@code ICancellableEvent} in this
     * NeoForge version — the subclass is definitely cancellable.
     *
     * <p>Command teleports ({@code /tp}, {@code /spreadplayers}) and player-driven teleports
     * (ender pearl, chorus fruit) are NOT this subclass, so they're naturally excluded — no
     * instanceof guards needed.
     */
    @SubscribeEvent
    public void onMobNaturalTeleport(net.neoforged.neoforge.event.entity.EntityTeleportEvent.EnderEntity e) {
        var entity = e.getEntity();
        if (entity == null) return;
        // Defensive: EnderEntity is for mobs, but never gate a player here regardless.
        if (entity instanceof ServerPlayer) return;
        if (!(entity.level() instanceof ServerLevel sl)) return;
        if (!mod.isProtectionActive(sl)) return;
        RegionManager mgr = mod.regions().get(sl);
        if (!mgr.testState(Flags.MOB_TELEPORT, null, e.getTargetX(), e.getTargetY(), e.getTargetZ())) {
            e.setCanceled(true);
        }
    }

    /**
     * Capture the player's position the moment they die. We need this for {@link #onRespawn}
     * to look up region-scoped {@code spawn} flags from the death location — the standard
     * PlayerRespawnEvent fires AFTER vanilla relocates the player to bed/world-spawn, so
     * the death position is lost. Stored per-player in {@link PlayerState}.
     */
    @SubscribeEvent
    public void onPlayerDeath(net.neoforged.neoforge.event.entity.living.LivingDeathEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        PlayerState st = states.computeIfAbsent(p.getUUID(), k -> new PlayerState());
        // Honour the per-world kill-switch like every other protection handler: in a world with
        // WGN disabled (useRegions=false), region keep-inventory / keep-xp / spawn-loc must NOT
        // take effect. Clear any snapshot and let vanilla handle the death. Gating here is enough
        // for the whole death chain — onLivingDrops/onLivingExpDropPlayer/onPlayerClone/onRespawn
        // all read this snapshot, so a cleared snapshot makes them no-op to vanilla.
        if (!mod.isProtectionActive(p.serverLevel())) {
            st.deathX = st.deathY = st.deathZ = Double.NaN;
            st.deathDim = null;
            st.deathKeepInv = false;
            st.deathKeepXp = false;
            return;
        }
        st.deathX = p.getX();
        st.deathY = p.getY();
        st.deathZ = p.getZ();
        st.deathDim = p.serverLevel().dimension();
        // Resolve keep-inventory / keep-xp ONCE here, at the authoritative death position,
        // and cache the decision. Both the drop-cancel and the clone-copy read this snapshot
        // so they can't disagree (see PlayerState.deathKeepInv doc).
        RegionManager mgr = mod.regions().get(p.serverLevel());
        st.deathKeepInv = mgr.testState(Flags.KEEP_INVENTORY, p.getUUID(), p.getX(), p.getY(), p.getZ());
        st.deathKeepXp  = mgr.testState(Flags.KEEP_XP,        p.getUUID(), p.getX(), p.getY(), p.getZ());
    }

    /**
     * Respawn relocation: when the player respawns, if their last region had a
     * {@code spawn} flag, teleport them there. Falls back to vanilla bed spawn otherwise.
     */
    @SubscribeEvent
    public void onRespawn(PlayerEvent.PlayerRespawnEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        try {
            // Look up the spawn flag at the DEATH position (snapshotted in onPlayerDeath),
            // not the current respawn position. Death must have happened in the same
            // dimension as we currently look in — cross-dim death respawn is exotic and
            // falls back to vanilla. NaN death position = no recorded death (e.g. fresh
            // join after world load), also fall back.
            PlayerState st = states.get(p.getUUID());
            RegionManager mgr;
            double dx, dy, dz;
            if (st != null && !Double.isNaN(st.deathX)
                    && st.deathDim == p.serverLevel().dimension()) {
                mgr = mod.regions().get(p.serverLevel());
                dx = st.deathX; dy = st.deathY; dz = st.deathZ;
                // Clear the snapshot so next-life doesn't reuse it.
                st.deathX = st.deathY = st.deathZ = Double.NaN;
                st.deathDim = null;
            } else {
                mgr = mod.regions().get(p.serverLevel());
                dx = p.getX(); dy = p.getY(); dz = p.getZ();
            }
            String spawnTarget = mgr.resolveValue(Flags.SPAWN_LOC, dx, dy, dz, p.getUUID());
            if (spawnTarget != null) {
                // Accept both comma- AND space-separated formats to match /rg teleport.
                String[] parts = spawnTarget.trim().split("[,\\s]+");
                if (parts.length >= 3) {
                    double tx = Double.parseDouble(parts[0]);
                    double ty = Double.parseDouble(parts[1]);
                    double tz = Double.parseDouble(parts[2]);
                    p.teleportTo(tx, ty, tz);
                }
            }
        } catch (Exception ex) {
            WorldGuardNeo.LOGGER.debug("spawn-flag respawn failed", ex);
        }
    }

    /* ---------------- Death-drop protection (keep-inventory / keep-xp) ---------------- */

    /**
     * Cancel item drops for a player who died inside a region with {@code keep-inventory =
     * ALLOW}. Without this, the items spawn on the ground at the death position and would
     * either despawn or be looted before the player can run back.
     *
     * <p>The companion {@link #onPlayerClone} actually preserves the inventory on the new
     * player object — cancelling drops alone doesn't help because vanilla clears the
     * player's inventory on respawn regardless. Both events must be handled together.
     */
    @SubscribeEvent
    public void onLivingDrops(net.neoforged.neoforge.event.entity.living.LivingDropsEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        // Read the snapshot taken in onPlayerDeath (which fires first). Falls back to a live
        // resolve if for some reason no snapshot exists (e.g. another mod fired LivingDropsEvent
        // without a preceding LivingDeathEvent — unusual but defensive).
        PlayerState st = states.get(p.getUUID());
        boolean keep;
        if (st != null && !Double.isNaN(st.deathX)) {
            keep = st.deathKeepInv;
        } else {
            RegionManager mgr = mod.regions().get(p.serverLevel());
            keep = mgr.testState(Flags.KEEP_INVENTORY, p.getUUID(), p.getX(), p.getY(), p.getZ());
        }
        if (keep) e.setCanceled(true);
    }

    /**
     * Suppress XP drop at the player's death position if {@code keep-xp = ALLOW} in the
     * region. Mirrors {@link #onLivingDrops} for the XP side.
     */
    @SubscribeEvent
    public void onLivingExpDropPlayer(net.neoforged.neoforge.event.entity.living.LivingExperienceDropEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        PlayerState st = states.get(p.getUUID());
        boolean keep;
        if (st != null && !Double.isNaN(st.deathX)) {
            keep = st.deathKeepXp;
        } else {
            RegionManager mgr = mod.regions().get(p.serverLevel());
            keep = mgr.testState(Flags.KEEP_XP, p.getUUID(), p.getX(), p.getY(), p.getZ());
        }
        if (keep) e.setDroppedExperience(0);
    }

    /**
     * On player respawn-after-death, copy the original inventory and (optionally) XP from
     * the old player object into the new one. Without this, even with {@code LivingDropsEvent}
     * cancelled the player still respawns empty because vanilla doesn't transfer the
     * inventory unless the gameRule {@code keepInventory} is true (which would apply to all
     * regions, not just protected ones).
     */
    @SubscribeEvent
    public void onPlayerClone(PlayerEvent.Clone e) {
        if (!e.isWasDeath()) return; // ignore end-portal / dim-change clones
        if (!(e.getEntity() instanceof ServerPlayer newP)) return;
        if (!(e.getOriginal() instanceof ServerPlayer oldP)) return;
        // Use the keep-inventory/xp decision snapshotted at death (in onPlayerDeath) so this
        // can't disagree with the drop-cancel handler. Fall back to a live resolve at the
        // old player's position if there's no snapshot (defensive — shouldn't happen since
        // LivingDeathEvent always precedes Clone for a death-respawn).
        PlayerState st = states.get(oldP.getUUID());
        boolean keepInv, keepXp;
        if (st != null && !Double.isNaN(st.deathX)) {
            keepInv = st.deathKeepInv;
            keepXp  = st.deathKeepXp;
        } else {
            RegionManager mgr = mod.regions().get(oldP.serverLevel());
            UUID id = oldP.getUUID();
            double x = oldP.getX(), y = oldP.getY(), z = oldP.getZ();
            keepInv = mgr.testState(Flags.KEEP_INVENTORY, id, x, y, z);
            keepXp  = mgr.testState(Flags.KEEP_XP,        id, x, y, z);
        }
        if (keepInv) {
            // replaceWith copies the whole vanilla inventory including armor slots and the
            // off-hand. It's how the keepInventory gameRule does it internally.
            try {
                newP.getInventory().replaceWith(oldP.getInventory());
            } catch (Throwable t) {
                WorldGuardNeo.LOGGER.debug("keep-inventory inventory copy failed", t);
            }
        }
        if (keepXp) {
            // Restore the XP level + progress exactly.
            newP.experienceLevel    = oldP.experienceLevel;
            newP.totalExperience    = oldP.totalExperience;
            newP.experienceProgress = oldP.experienceProgress;
        }
    }

    /* ---------------- Cleanup ---------------- */

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        mod.expiry().record(e.getEntity().getUUID()); // claim-expiry activity tracking
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        mod.expiry().record(e.getEntity().getUUID()); // mark active at the moment of leaving
        states.remove(e.getEntity().getUUID());
        lastPickupDenyMsg.remove(e.getEntity().getUUID());
        mod.selections().clear(e.getEntity().getUUID()); // drop the player's pending selection
    }

    @SubscribeEvent
    public void onChangeDim(PlayerEvent.PlayerChangedDimensionEvent e) {
        // Wipe region snapshot so greetings fire again in the new world.
        PlayerState st = states.get(e.getEntity().getUUID());
        if (st != null) { st.lastRegions = Set.of(); st.lastSafeValid = false; }
        // Drop the pending selection — its corners belong to the old world, and clearing here
        // also removes any lingering CUI outline on the client after the dimension hop.
        mod.selections().clear(e.getEntity().getUUID());
    }
}
