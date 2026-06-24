package dev.thefather007.worldguardneo.gametest;

import dev.thefather007.worldguardneo.WorldGuardNeo;
import dev.thefather007.worldguardneo.flags.BooleanFlag;
import dev.thefather007.worldguardneo.flags.DoubleFlag;
import dev.thefather007.worldguardneo.flags.Flag;
import dev.thefather007.worldguardneo.flags.Flags;
import dev.thefather007.worldguardneo.flags.IntegerFlag;
import dev.thefather007.worldguardneo.flags.SetFlag;
import dev.thefather007.worldguardneo.flags.StateFlag;
import dev.thefather007.worldguardneo.flags.StringFlag;
import dev.thefather007.worldguardneo.mixinsupport.MixinFlagBridge;
import dev.thefather007.worldguardneo.region.CuboidRegion;
import dev.thefather007.worldguardneo.region.RegionGroup;
import dev.thefather007.worldguardneo.region.ProtectedRegion;
import dev.thefather007.worldguardneo.region.RegionManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * In-game GameTest battery for WorldGuardNeo, exercising the REAL event/mixin enforcement path
 * inside a live {@link ServerLevel} — the layer the standalone JVM unit tests in {@code tests/}
 * can't reach. Each test creates a region over the test platform, performs a real world action
 * (a mock player breaking/using a block, a forced random tick, spawning an entity, mounting a
 * vehicle, …) and asserts the protection outcome.
 *
 * <h2>Running</h2>
 * These run via the dedicated GameTest server (the {@code gameTestServer} Gradle run —
 * {@code ./gradlew runGameTestServer}), or in-game with {@code /test runall} on a server that has
 * WorldGuardNeo's gametest namespace enabled. No other mods are required: WorldGuardNeo has no hard
 * dependencies and the regions here are created programmatically.
 *
 * <p>All tests share the {@code worldguardneo:platform} structure (a 9×6×9 stone floor).
 */
@GameTestHolder(WorldGuardNeo.MOD_ID)
@PrefixGameTestTemplate(false)
public final class WGNGameTests {

    // Bare path only: @GameTestHolder supplies the "worldguardneo" namespace and the framework
    // builds ResourceLocation(namespace, TPL). A namespaced value here would double the namespace
    // ("worldguardneo:worldguardneo:platform" → invalid path). The template (a 9x6x9 stone floor)
    // is shipped as a binary structure NBT at data/worldguardneo/structure/platform.nbt — the
    // datapack path StructureTemplateManager.loadFromResource reads. NOTE: the folder is the
    // SINGULAR "structure" (MC 1.21 renamed the datapack dir from "structures"); the converter is
    // FileToIdConverter("structure", ".nbt"). loadFromTestStructures only reads the run-dir
    // filesystem, so the template can't be shipped in the jar that way.
    private static final String TPL = "platform";

    /* ---------------- helpers ---------------- */

    private static RegionManager mgr(GameTestHelper h) {
        return WorldGuardNeo.get().regions().get(h.getLevel());
    }

    /** Create (replacing any prior) a region covering the whole platform, return it. */
    private static CuboidRegion region(GameTestHelper h, String id) {
        RegionManager m = mgr(h);
        m.remove(id);
        BlockPos a = h.absolutePos(new BlockPos(0, 0, 0));
        BlockPos b = h.absolutePos(new BlockPos(8, 5, 8));
        CuboidRegion r = new CuboidRegion(id,
                new dev.thefather007.worldguardneo.util.Vec3(a.getX(), a.getY(), a.getZ()),
                new dev.thefather007.worldguardneo.util.Vec3(b.getX(), b.getY(), b.getZ()));
        m.add(r);
        return r;
    }

    @SuppressWarnings("removal") // makeMockServerPlayerInLevel is the supported mock for 1.21.1
    private static ServerPlayer stranger(GameTestHelper h) {
        return h.makeMockServerPlayerInLevel();
    }

    private static void forceRandomTicks(GameTestHelper h, BlockPos abs, int n) {
        ServerLevel lvl = h.getLevel();
        for (int i = 0; i < n; i++) {
            BlockState st = lvl.getBlockState(abs);
            st.randomTick(lvl, abs, lvl.getRandom());
        }
    }

    private static void placeBlockOnTop(GameTestHelper h, ServerPlayer p, BlockPos floorRel,
                                        net.minecraft.world.item.Item item) {
        ItemStack stack = new ItemStack(item, 64);
        p.setItemInHand(InteractionHand.MAIN_HAND, stack);
        BlockPos floorAbs = h.absolutePos(floorRel);
        p.setPos(floorAbs.getX() + 0.5, floorAbs.getY() + 2.0, floorAbs.getZ() + 0.5); // stand above (reach)
        Vec3 hitVec = new Vec3(floorAbs.getX() + 0.5, floorAbs.getY() + 1.0, floorAbs.getZ() + 0.5);
        BlockHitResult hit = new BlockHitResult(hitVec, Direction.UP, floorAbs, false);
        p.gameMode.useItemOn(p, h.getLevel(), stack, InteractionHand.MAIN_HAND, hit);
    }

    /**
     * Right-click (empty hand) the block at {@code rel} as {@code p}, driving the action through the
     * real {@link net.minecraft.server.level.ServerPlayerGameMode#useItemOn} path — which fires the
     * {@code PlayerInteractEvent.RightClickBlock} the mod enforces. NOTE: {@link GameTestHelper#useBlock}
     * must NOT be used to test interact/chest protection: it calls the block's interaction methods
     * directly, bypassing the event entirely (a denied region would still "open" the block), and it
     * re-applies {@code absolutePos} internally (so passing an already-absolute pos double-offsets).
     */
    private static void useBlockAsPlayer(GameTestHelper h, ServerPlayer p, BlockPos rel) {
        p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        BlockPos abs = h.absolutePos(rel);
        p.setPos(abs.getX() + 0.5, abs.getY() + 1.0, abs.getZ() + 0.5); // stand on the block (reach)
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(abs), Direction.UP, abs, false);
        p.gameMode.useItemOn(p, h.getLevel(), ItemStack.EMPTY, InteractionHand.MAIN_HAND, hit);
    }

    /**
     * A mock player positioned at the given relative position INSIDE the test region, with the
     * forced-creative invulnerability cleared so it can actually be damaged. pvp/mob-damage flags
     * are resolved at the victim's position, and a freshly mocked player sits at the level spawn
     * (wilderness), so without this it would never see the region's flag.
     */
    private static ServerPlayer combatantAt(GameTestHelper h, BlockPos rel) {
        ServerPlayer p = stranger(h);
        BlockPos abs = h.absolutePos(rel);
        p.setPos(abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5);
        p.getAbilities().invulnerable = false;
        return p;
    }

    /**
     * Fire the mod's pvp/mob-damage gate ({@code LivingIncomingDamageEvent}) for {@code attacker}
     * hitting {@code victim} and report whether the mod cancelled it. Posting the event directly is
     * deterministic — unlike trying to land real damage on a mock player, whose forced creative
     * invulnerability and lack of a client connection make {@code hurt()} unreliable in the headless
     * GameTestServer. The verdict (cancel/allow) is exactly what the mod computes from the PVP flag.
     */
    private static boolean attackGateCancels(ServerPlayer attacker,
                                             net.minecraft.world.entity.LivingEntity victim, float amount) {
        var container = new net.neoforged.neoforge.common.damagesource.DamageContainer(
                attacker.damageSources().playerAttack(attacker), amount);
        var evt = new net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent(victim, container);
        NeoForge.EVENT_BUS.post(evt);
        return evt.isCanceled();
    }

    /**
     * Empty a water bucket onto the top of {@code targetRel}. The mod's {@code bucket-empty} gate is
     * on {@code RightClickBlock}, so we post that event first (exactly what vanilla fires when a
     * bucket right-clicks a block) and only perform the real empty — {@link
     * net.minecraft.world.item.BucketItem#use} raycasts from the eyes, hence the look-straight-down —
     * when the gate did not cancel. This mirrors the real client→server handshake (a cancelled
     * RightClickBlock means the client never sends the follow-up use-item).
     */
    private static void emptyWaterBucket(GameTestHelper h, ServerPlayer p, BlockPos targetRel) {
        ItemStack bucket = new ItemStack(Items.WATER_BUCKET);
        p.setItemInHand(InteractionHand.MAIN_HAND, bucket);
        BlockPos abs = h.absolutePos(targetRel);
        p.setPos(abs.getX() + 0.5, abs.getY() + 2.0, abs.getZ() + 0.5);
        p.setXRot(90f);  // look straight down so BucketItem#use raycasts onto the target's top face
        p.setYRot(0f);
        BlockHitResult hit = new BlockHitResult(
                new Vec3(abs.getX() + 0.5, abs.getY() + 1.0, abs.getZ() + 0.5), Direction.UP, abs, false);
        PlayerInteractEvent.RightClickBlock evt =
                new PlayerInteractEvent.RightClickBlock(p, InteractionHand.MAIN_HAND, abs, hit);
        NeoForge.EVENT_BUS.post(evt);
        if (!evt.isCanceled()) {
            p.gameMode.useItem(p, h.getLevel(), bucket, InteractionHand.MAIN_HAND);
        }
    }

    /* ---------------- BLOCK-BREAK / BUILD ---------------- */

    @GameTest(template = TPL)
    public static void breakDeniedForStranger(GameTestHelper h) {
        region(h, "gt_break_deny");
        BlockPos rel = new BlockPos(4, 1, 4);
        h.setBlock(rel, Blocks.STONE);
        ServerPlayer p = stranger(h);
        p.gameMode.destroyBlock(h.absolutePos(rel));
        h.assertBlockPresent(Blocks.STONE, rel); // membership: stranger can't break
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void breakAllowedForOwner(GameTestHelper h) {
        CuboidRegion r = region(h, "gt_break_owner");
        ServerPlayer p = stranger(h);
        r.owners().add(p.getUUID());
        BlockPos rel = new BlockPos(4, 1, 4);
        h.setBlock(rel, Blocks.STONE);
        p.gameMode.destroyBlock(h.absolutePos(rel));
        h.assertBlockNotPresent(Blocks.STONE, rel); // owner may break
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void breakAllowFlagOpensToStranger(GameTestHelper h) {
        CuboidRegion r = region(h, "gt_break_allow");
        r.setFlag(Flags.BLOCK_BREAK, StateFlag.State.ALLOW);
        r.setFlag(Flags.BUILD, StateFlag.State.ALLOW);
        BlockPos rel = new BlockPos(4, 1, 4);
        h.setBlock(rel, Blocks.STONE);
        ServerPlayer p = stranger(h);
        p.gameMode.destroyBlock(h.absolutePos(rel));
        h.assertBlockNotPresent(Blocks.STONE, rel); // explicit allow lets a stranger break
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void breakExplicitDenyBlocksOwner(GameTestHelper h) {
        CuboidRegion r = region(h, "gt_break_owner_deny");
        ServerPlayer p = stranger(h);
        r.owners().add(p.getUUID());
        r.setFlag(Flags.BLOCK_BREAK, StateFlag.State.DENY); // explicit deny beats membership
        BlockPos rel = new BlockPos(4, 1, 4);
        h.setBlock(rel, Blocks.STONE);
        p.gameMode.destroyBlock(h.absolutePos(rel));
        h.assertBlockPresent(Blocks.STONE, rel);
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void breakWildernessAllowed(GameTestHelper h) {
        // No region created → wilderness → vanilla break allowed.
        mgr(h); // ensure manager exists
        BlockPos rel = new BlockPos(4, 1, 4);
        h.setBlock(rel, Blocks.STONE);
        ServerPlayer p = stranger(h);
        p.gameMode.destroyBlock(h.absolutePos(rel));
        h.assertBlockNotPresent(Blocks.STONE, rel);
        h.succeed();
    }

    /* ---------------- INTERACT / USE ---------------- */

    @GameTest(template = TPL)
    public static void interactTrapdoorDeniedForStranger(GameTestHelper h) {
        region(h, "gt_use_deny");
        BlockPos rel = new BlockPos(4, 1, 4);
        h.setBlock(rel, Blocks.OAK_TRAPDOOR);
        ServerPlayer p = stranger(h);
        useBlockAsPlayer(h, p, rel);
        h.assertBlockProperty(rel, BlockStateProperties.OPEN, false); // didn't open
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void interactTrapdoorAllowedForMember(GameTestHelper h) {
        CuboidRegion r = region(h, "gt_use_member");
        ServerPlayer p = stranger(h);
        r.members().add(p.getUUID());
        BlockPos rel = new BlockPos(4, 1, 4);
        h.setBlock(rel, Blocks.OAK_TRAPDOOR);
        useBlockAsPlayer(h, p, rel);
        h.assertBlockProperty(rel, BlockStateProperties.OPEN, true); // member toggled it open
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void interactAllowFlagOpensToStranger(GameTestHelper h) {
        CuboidRegion r = region(h, "gt_use_allow");
        r.setFlag(Flags.INTERACT, StateFlag.State.ALLOW);
        r.setFlag(Flags.USE, StateFlag.State.ALLOW);
        BlockPos rel = new BlockPos(4, 1, 4);
        h.setBlock(rel, Blocks.OAK_TRAPDOOR);
        ServerPlayer p = stranger(h);
        useBlockAsPlayer(h, p, rel);
        h.assertBlockProperty(rel, BlockStateProperties.OPEN, true);
        h.succeed();
    }

    /* ---------------- RANDOM-TICK (mixins) ---------------- */

    @GameTest(template = TPL)
    public static void leafDecayDenyKeepsLeaves(GameTestHelper h) {
        CuboidRegion r = region(h, "gt_leaf_deny");
        r.setFlag(Flags.LEAF_DECAY, StateFlag.State.DENY);
        BlockPos rel = new BlockPos(4, 2, 4);
        // Non-persistent leaves at max distance would normally decay on random tick.
        h.setBlock(rel, Blocks.OAK_LEAVES.defaultBlockState()
                .setValue(BlockStateProperties.PERSISTENT, false)
                .setValue(BlockStateProperties.DISTANCE, 7));
        forceRandomTicks(h, h.absolutePos(rel), 200);
        h.assertBlockPresent(Blocks.OAK_LEAVES, rel); // deny → leaves survive
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void leafDecayAllowDecays(GameTestHelper h) {
        // No region → wilderness → leaf decay proceeds (control: proves the test setup decays).
        mgr(h);
        BlockPos rel = new BlockPos(4, 2, 4);
        h.setBlock(rel, Blocks.OAK_LEAVES.defaultBlockState()
                .setValue(BlockStateProperties.PERSISTENT, false)
                .setValue(BlockStateProperties.DISTANCE, 7));
        forceRandomTicks(h, h.absolutePos(rel), 400);
        h.assertBlockNotPresent(Blocks.OAK_LEAVES, rel);
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void iceMeltDenyKeepsIce(GameTestHelper h) {
        CuboidRegion r = region(h, "gt_ice_deny");
        r.setFlag(Flags.ICE_MELT, StateFlag.State.DENY);
        BlockPos rel = new BlockPos(4, 1, 4);
        h.setBlock(rel, Blocks.ICE);
        forceRandomTicks(h, h.absolutePos(rel), 200);
        h.assertBlockPresent(Blocks.ICE, rel); // mixin cancels the melt tick
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void snowMeltDenyKeepsSnow(GameTestHelper h) {
        CuboidRegion r = region(h, "gt_snow_deny");
        r.setFlag(Flags.SNOW_MELT, StateFlag.State.DENY);
        BlockPos rel = new BlockPos(4, 1, 4);
        h.setBlock(rel, Blocks.SNOW);
        forceRandomTicks(h, h.absolutePos(rel), 200);
        h.assertBlockPresent(Blocks.SNOW, rel);
        h.succeed();
    }

    /* ---------------- ENTITY EVENTS ---------------- */

    @GameTest(template = TPL)
    public static void lightningDenyRemovesBolt(GameTestHelper h) {
        CuboidRegion r = region(h, "gt_lightning_deny");
        r.setFlag(Flags.LIGHTNING, StateFlag.State.DENY);
        h.spawn(EntityType.LIGHTNING_BOLT, new BlockPos(4, 1, 4));
        // EntityJoinLevelEvent is cancelled → the bolt never joins the level.
        h.assertEntityNotPresent(EntityType.LIGHTNING_BOLT);
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void lightningAllowKeepsBolt(GameTestHelper h) {
        region(h, "gt_lightning_allow").setFlag(Flags.LIGHTNING, StateFlag.State.ALLOW);
        LightningBolt bolt = h.spawn(EntityType.LIGHTNING_BOLT, new BlockPos(4, 1, 4));
        h.assertTrue(bolt.isAlive(), "lightning allowed → bolt spawned");
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void pvpDenyNoDamage(GameTestHelper h) {
        CuboidRegion r = region(h, "gt_pvp_deny");
        r.setFlag(Flags.PVP, StateFlag.State.DENY);
        BlockPos rel = new BlockPos(4, 1, 4);
        ServerPlayer attacker = combatantAt(h, rel);
        ServerPlayer victim = combatantAt(h, rel);
        // Victim sits inside the region so PVP=DENY applies; the mod must cancel the damage gate.
        h.assertTrue(attackGateCancels(attacker, victim, 4.0f), "pvp deny → damage gate cancels");
        h.succeed();
    }

    /* ---------------- BLOCK-PLACE ---------------- */

    @GameTest(template = TPL)
    public static void placeDeniedForStranger(GameTestHelper h) {
        region(h, "gt_place_deny");
        BlockPos floorRel = new BlockPos(4, 1, 4); // pedestal inside the region; place lands on (4,2,4)
        h.setBlock(floorRel, Blocks.STONE);
        ServerPlayer p = stranger(h);
        placeBlockOnTop(h, p, floorRel, Items.STONE);
        h.assertBlockNotPresent(Blocks.STONE, floorRel.above()); // stranger can't place
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void placeAllowedForOwner(GameTestHelper h) {
        CuboidRegion r = region(h, "gt_place_owner");
        ServerPlayer p = stranger(h);
        r.owners().add(p.getUUID());
        BlockPos floorRel = new BlockPos(4, 1, 4); // pedestal inside the region; place lands on (4,2,4)
        h.setBlock(floorRel, Blocks.STONE);
        placeBlockOnTop(h, p, floorRel, Items.STONE);
        h.assertBlockPresent(Blocks.STONE, floorRel.above());
        h.succeed();
    }

    /* ---------------- CHEST-ACCESS ---------------- */

    @GameTest(template = TPL)
    public static void chestAccessDeniedForStranger(GameTestHelper h) {
        region(h, "gt_chest_deny");
        BlockPos rel = new BlockPos(4, 1, 4);
        h.setBlock(rel, Blocks.CHEST);
        ServerPlayer p = stranger(h);
        useBlockAsPlayer(h, p, rel);
        h.assertTrue(p.containerMenu == p.inventoryMenu, "stranger: chest menu did not open");
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void chestAccessAllowedForMember(GameTestHelper h) {
        CuboidRegion r = region(h, "gt_chest_member");
        ServerPlayer p = stranger(h);
        r.members().add(p.getUUID());
        BlockPos rel = new BlockPos(4, 1, 4);
        h.setBlock(rel, Blocks.CHEST);
        useBlockAsPlayer(h, p, rel);
        h.assertTrue(p.containerMenu != p.inventoryMenu, "member: chest menu opened");
        h.succeed();
    }

    /* ---------------- PvP / MOB-DAMAGE / VEHICLE / DECORATION ---------------- */

    @GameTest(template = TPL)
    public static void mobDamageDenyProtectsMob(GameTestHelper h) {
        region(h, "gt_mobdmg_deny").setFlag(Flags.MOB_DAMAGE, StateFlag.State.DENY);
        Cow cow = h.spawn(EntityType.COW, new BlockPos(4, 1, 4));
        float before = cow.getHealth();
        stranger(h).attack(cow);
        h.assertTrue(cow.getHealth() == before, "mob-damage deny → cow unharmed");
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void vehicleProtectedFromDestruction(GameTestHelper h) {
        region(h, "gt_vehicle"); // protect-vehicles defaults true; vehicle-destroy also gates
        Boat boat = h.spawn(EntityType.BOAT, new BlockPos(4, 1, 4));
        stranger(h).attack(boat);
        h.assertTrue(boat.isAlive(), "vehicle survives a stranger's attack in a region");
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void decorationProtectedFromStranger(GameTestHelper h) {
        region(h, "gt_frame");
        ItemFrame frame = h.spawn(EntityType.ITEM_FRAME, new BlockPos(4, 1, 4));
        stranger(h).attack(frame);
        h.assertTrue(frame.isAlive(), "item frame survives a stranger's attack");
        h.succeed();
    }

    /* ---------------- EXPLOSIONS ---------------- */

    @GameTest(template = TPL)
    public static void explosionProtectsRegionBlocks(GameTestHelper h) {
        region(h, "gt_explode");
        // Ring of stone around the blast point, all inside the region.
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            h.setBlock(new BlockPos(4 + dx, 1, 4 + dz), Blocks.STONE);
        }
        BlockPos centerAbs = h.absolutePos(new BlockPos(4, 1, 4));
        h.getLevel().explode(null, centerAbs.getX() + 0.5, centerAbs.getY() + 0.5, centerAbs.getZ() + 0.5,
                4.0f, Level.ExplosionInteraction.TNT);
        // Region blocks must survive (disable-explosions-around-regions defaults true; the
        // per-explosion flags also gate it).
        h.assertBlockPresent(Blocks.STONE, new BlockPos(4, 1, 4));
        h.assertBlockPresent(Blocks.STONE, new BlockPos(5, 1, 4));
        h.succeed();
    }

    /* ---------------- v1.3 PROTECTIONS (vehicle-enter / item-frame-rotate / buckets) ---------------- */

    @GameTest(template = TPL)
    public static void vehicleEnterDenyBlocksBoarding(GameTestHelper h) {
        region(h, "gt_venter_deny").setFlag(Flags.VEHICLE_ENTER, StateFlag.State.DENY);
        Boat boat = h.spawn(EntityType.BOAT, new BlockPos(4, 1, 4));
        ServerPlayer p = stranger(h);
        p.startRiding(boat); // fires EntityMountEvent → vehicle-enter gate cancels it
        h.assertFalse(p.isPassenger(), "vehicle-enter deny → player did not board");
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void vehicleEnterAllowedByDefault(GameTestHelper h) {
        region(h, "gt_venter_allow"); // flag unset → default allow
        Boat boat = h.spawn(EntityType.BOAT, new BlockPos(4, 1, 4));
        ServerPlayer p = stranger(h);
        p.startRiding(boat);
        h.assertTrue(p.isPassenger(), "vehicle-enter default allow → player boarded");
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void itemFrameRotateDenyKeepsRotation(GameTestHelper h) {
        CuboidRegion r = region(h, "gt_framerot_deny");
        r.setFlag(Flags.ITEM_FRAME_ROTATE, StateFlag.State.DENY);
        ServerPlayer p = stranger(h);
        r.members().add(p.getUUID()); // member: build-access passes, so we isolate the rotate flag
        ItemFrame frame = h.spawn(EntityType.ITEM_FRAME, new BlockPos(4, 1, 4));
        frame.setItem(new ItemStack(Items.DIAMOND)); // filled → right-click rotates
        int before = frame.getRotation();
        p.interactOn(frame, InteractionHand.MAIN_HAND); // rotate attempt
        h.assertTrue(frame.getRotation() == before, "item-frame-rotate deny → rotation unchanged");
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void itemFrameRotateAllowedForMember(GameTestHelper h) {
        CuboidRegion r = region(h, "gt_framerot_allow");
        ServerPlayer p = stranger(h);
        r.members().add(p.getUUID());
        ItemFrame frame = h.spawn(EntityType.ITEM_FRAME, new BlockPos(4, 1, 4));
        frame.setItem(new ItemStack(Items.DIAMOND));
        int before = frame.getRotation();
        p.interactOn(frame, InteractionHand.MAIN_HAND);
        h.assertTrue(frame.getRotation() != before, "default allow → member rotated the frame");
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void bucketEmptyDenyBlocksWater(GameTestHelper h) {
        CuboidRegion r = region(h, "gt_bucket_deny");
        r.setFlag(Flags.BUCKET_EMPTY, StateFlag.State.DENY);
        ServerPlayer p = stranger(h);
        r.members().add(p.getUUID()); // member: interact passes, so we isolate bucket-empty
        BlockPos floorRel = new BlockPos(4, 1, 4); // emptying onto the top face places at (4,2,4)
        h.setBlock(floorRel, Blocks.STONE);
        emptyWaterBucket(h, p, floorRel);
        h.assertBlockNotPresent(Blocks.WATER, floorRel.above()); // bucket-empty deny → no water
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void bucketEmptyAllowedForMember(GameTestHelper h) {
        CuboidRegion r = region(h, "gt_bucket_allow");
        ServerPlayer p = stranger(h);
        r.members().add(p.getUUID());
        BlockPos floorRel = new BlockPos(4, 1, 4);
        h.setBlock(floorRel, Blocks.STONE);
        emptyWaterBucket(h, p, floorRel);
        h.assertBlockPresent(Blocks.WATER, floorRel.above()); // default allow → water placed
        h.succeed();
    }

    /* ---------------- ENGINE (live ServerLevel integration) ---------------- */

    @GameTest(template = TPL)
    public static void engineRegionAtPositionLive(GameTestHelper h) {
        CuboidRegion r = region(h, "gt_engine_at");
        BlockPos abs = h.absolutePos(new BlockPos(4, 1, 4));
        List<ProtectedRegion> here = mgr(h).getApplicable(abs.getX(), abs.getY(), abs.getZ());
        h.assertTrue(here.size() == 1 && here.get(0) == r, "region resolves at its own position");
        BlockPos out = h.absolutePos(new BlockPos(4, 1, 4)).offset(100, 0, 100);
        h.assertTrue(mgr(h).getApplicable(out.getX(), out.getY(), out.getZ()).isEmpty(),
                "no region 100 blocks away");
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void enginePriorityAndMembershipLive(GameTestHelper h) {
        CuboidRegion r = region(h, "gt_engine_member");
        ServerPlayer p = stranger(h);
        BlockPos abs = h.absolutePos(new BlockPos(4, 1, 4));
        h.assertTrue(!mgr(h).testBuildAccess(Flags.BUILD, abs.getX(), abs.getY(), abs.getZ(), p.getUUID()),
                "stranger denied build by membership");
        r.members().add(p.getUUID());
        h.assertTrue(mgr(h).testBuildAccess(Flags.BUILD, abs.getX(), abs.getY(), abs.getZ(), p.getUUID()),
                "member allowed build");
        h.succeed();
    }

    /* ---------------- ADDED: break / place / interact variants & controls ---------------- */

    @GameTest(template = TPL)
    public static void breakAllowedForMember(GameTestHelper h) {
        CuboidRegion r = region(h, "gt_break_member");
        ServerPlayer p = stranger(h);
        r.members().add(p.getUUID());
        BlockPos rel = new BlockPos(4, 1, 4);
        h.setBlock(rel, Blocks.STONE);
        p.gameMode.destroyBlock(h.absolutePos(rel));
        h.assertBlockNotPresent(Blocks.STONE, rel); // member may break
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void placeAllowFlagOpensToStranger(GameTestHelper h) {
        CuboidRegion r = region(h, "gt_place_allow");
        // Placing a block goes through BOTH the interact gate (RightClickBlock) and the build gate
        // (EntityPlaceEvent), so a stranger needs interact/use opened as well as block-place/build.
        r.setFlag(Flags.INTERACT, StateFlag.State.ALLOW);
        r.setFlag(Flags.USE, StateFlag.State.ALLOW);
        r.setFlag(Flags.BLOCK_PLACE, StateFlag.State.ALLOW);
        r.setFlag(Flags.BUILD, StateFlag.State.ALLOW);
        BlockPos floorRel = new BlockPos(4, 1, 4); // pedestal inside the region; place lands on (4,2,4)
        h.setBlock(floorRel, Blocks.STONE);
        placeBlockOnTop(h, stranger(h), floorRel, Items.STONE);
        h.assertBlockPresent(Blocks.STONE, floorRel.above());
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void placeExplicitDenyBlocksOwner(GameTestHelper h) {
        CuboidRegion r = region(h, "gt_place_owner_deny");
        ServerPlayer p = stranger(h);
        r.owners().add(p.getUUID());
        r.setFlag(Flags.BLOCK_PLACE, StateFlag.State.DENY); // explicit deny beats membership
        BlockPos floorRel = new BlockPos(4, 1, 4); // pedestal inside the region; place lands on (4,2,4)
        h.setBlock(floorRel, Blocks.STONE);
        placeBlockOnTop(h, p, floorRel, Items.STONE);
        h.assertBlockNotPresent(Blocks.STONE, floorRel.above());
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void placeWildernessAllowed(GameTestHelper h) {
        mgr(h);
        BlockPos floorRel = new BlockPos(4, 1, 4); // pedestal (wilderness — no region); lands on (4,2,4)
        h.setBlock(floorRel, Blocks.STONE);
        placeBlockOnTop(h, stranger(h), floorRel, Items.STONE);
        h.assertBlockPresent(Blocks.STONE, floorRel.above());
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void interactExplicitDenyBlocksMember(GameTestHelper h) {
        CuboidRegion r = region(h, "gt_use_member_deny");
        ServerPlayer p = stranger(h);
        r.members().add(p.getUUID());
        r.setFlag(Flags.INTERACT, StateFlag.State.DENY); // explicit deny beats membership
        BlockPos rel = new BlockPos(4, 1, 4);
        h.setBlock(rel, Blocks.OAK_TRAPDOOR);
        useBlockAsPlayer(h, p, rel);
        h.assertBlockProperty(rel, BlockStateProperties.OPEN, false);
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void interactWildernessAllowed(GameTestHelper h) {
        mgr(h);
        BlockPos rel = new BlockPos(4, 1, 4);
        h.setBlock(rel, Blocks.OAK_TRAPDOOR);
        useBlockAsPlayer(h, stranger(h), rel);
        h.assertBlockProperty(rel, BlockStateProperties.OPEN, true);
        h.succeed();
    }

    /* ---------------- ADDED: chest-access variants ---------------- */

    @GameTest(template = TPL)
    public static void chestAccessAllowFlagOpensToStranger(GameTestHelper h) {
        CuboidRegion r = region(h, "gt_chest_allow");
        r.setFlag(Flags.INTERACT, StateFlag.State.ALLOW);
        r.setFlag(Flags.USE, StateFlag.State.ALLOW);
        r.setFlag(Flags.CHEST_ACCESS, StateFlag.State.ALLOW);
        BlockPos rel = new BlockPos(4, 1, 4);
        h.setBlock(rel, Blocks.CHEST);
        ServerPlayer p = stranger(h);
        useBlockAsPlayer(h, p, rel);
        h.assertTrue(p.containerMenu != p.inventoryMenu, "chest-access allow → chest opened");
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void chestAccessExplicitDenyBlocksMember(GameTestHelper h) {
        CuboidRegion r = region(h, "gt_chest_member_deny");
        ServerPlayer p = stranger(h);
        r.members().add(p.getUUID());
        r.setFlag(Flags.CHEST_ACCESS, StateFlag.State.DENY);
        BlockPos rel = new BlockPos(4, 1, 4);
        h.setBlock(rel, Blocks.CHEST);
        useBlockAsPlayer(h, p, rel);
        h.assertTrue(p.containerMenu == p.inventoryMenu, "chest-access deny → chest stayed closed for member");
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void chestAccessWildernessOpens(GameTestHelper h) {
        mgr(h);
        BlockPos rel = new BlockPos(4, 1, 4);
        h.setBlock(rel, Blocks.CHEST);
        ServerPlayer p = stranger(h);
        useBlockAsPlayer(h, p, rel);
        h.assertTrue(p.containerMenu != p.inventoryMenu, "wilderness → chest opens");
        h.succeed();
    }

    /* ---------------- ADDED: pvp / mob-damage controls ---------------- */

    @GameTest(template = TPL)
    public static void pvpAllowFlagLetsDamage(GameTestHelper h) {
        region(h, "gt_pvp_allow").setFlag(Flags.PVP, StateFlag.State.ALLOW);
        BlockPos rel = new BlockPos(4, 1, 4);
        ServerPlayer attacker = combatantAt(h, rel);
        ServerPlayer victim = combatantAt(h, rel);
        // Victim sits inside the region (PVP=ALLOW), so the mod must NOT cancel the damage gate.
        h.assertTrue(!attackGateCancels(attacker, victim, 4.0f), "pvp allow → damage gate does not cancel");
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void mobDamageAllowFlagLetsDamage(GameTestHelper h) {
        region(h, "gt_mobdmg_allow").setFlag(Flags.MOB_DAMAGE, StateFlag.State.ALLOW);
        Cow cow = h.spawn(EntityType.COW, new BlockPos(4, 1, 4));
        float before = cow.getHealth();
        stranger(h).attack(cow);
        h.assertTrue(cow.getHealth() < before, "mob-damage allow → cow took damage");
        h.succeed();
    }

    /* ---------------- ADDED: random-tick mixin variants ---------------- */

    @GameTest(template = TPL)
    public static void frostedIceMeltDenyKeepsIce(GameTestHelper h) {
        CuboidRegion r = region(h, "gt_frosted_deny");
        r.setFlag(Flags.FROSTED_ICE_MELT, StateFlag.State.DENY);
        BlockPos rel = new BlockPos(4, 1, 4);
        h.setBlock(rel, Blocks.FROSTED_ICE);
        forceRandomTicks(h, h.absolutePos(rel), 300);
        h.assertBlockPresent(Blocks.FROSTED_ICE, rel); // mixin cancels frosted-ice melt
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void leafDecayUnsetStillDecays(GameTestHelper h) {
        // Region present but no leaf-decay flag → leaves still decay (unset = default allow).
        region(h, "gt_leaf_unset");
        BlockPos rel = new BlockPos(4, 2, 4);
        h.setBlock(rel, Blocks.OAK_LEAVES.defaultBlockState()
                .setValue(BlockStateProperties.PERSISTENT, false)
                .setValue(BlockStateProperties.DISTANCE, 7));
        forceRandomTicks(h, h.absolutePos(rel), 400);
        h.assertBlockNotPresent(Blocks.OAK_LEAVES, rel);
        h.succeed();
    }

    /* ============================================================================
     *  EXTENDED COVERAGE — every remaining flag and the resolution engine.
     *  Techniques (all proven deterministic in the headless GameTestServer):
     *   - post the exact NeoForge event the mod listens on and assert cancel
     *     (damage gate, right-click gate);
     *   - call MixinFlagBridge#check, the gate the random-tick mixins delegate to;
     *   - resolve value/state flags through the live RegionManager engine.
     * ========================================================================== */

    /* ---------------- extended helpers ---------------- */

    /** A region with custom RELATIVE bounds (everything stays in this test's own arena). */
    private static CuboidRegion makeRegion(GameTestHelper h, String id, BlockPos relA, BlockPos relB) {
        RegionManager m = mgr(h);
        m.remove(id);
        BlockPos a = h.absolutePos(relA), b = h.absolutePos(relB);
        CuboidRegion r = new CuboidRegion(id,
                new dev.thefather007.worldguardneo.util.Vec3(a.getX(), a.getY(), a.getZ()),
                new dev.thefather007.worldguardneo.util.Vec3(b.getX(), b.getY(), b.getZ()));
        m.add(r);
        return r;
    }

    /** Add a fresh stranger to {@code r}'s members and return it. */
    private static ServerPlayer memberOf(GameTestHelper h, CuboidRegion r) {
        ServerPlayer p = stranger(h);
        r.members().add(p.getUUID());
        return p;
    }

    /** Post the mod's damage gate for a player victim standing at {@code rel}; report cancel. */
    private static boolean damageGateCancels(GameTestHelper h, BlockPos rel,
                                             net.minecraft.world.damagesource.DamageSource src) {
        ServerPlayer victim = combatantAt(h, rel);
        var c = new net.neoforged.neoforge.common.damagesource.DamageContainer(src, 4.0f);
        var evt = new net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent(victim, c);
        NeoForge.EVENT_BUS.post(evt);
        return evt.isCanceled();
    }

    /** Post a RightClickBlock for {@code p} clicking {@code rel} holding {@code held}; report cancel. */
    private static boolean rightClickGateCancels(GameTestHelper h, ServerPlayer p, BlockPos rel, ItemStack held) {
        p.setItemInHand(InteractionHand.MAIN_HAND, held);
        BlockPos abs = h.absolutePos(rel);
        p.setPos(abs.getX() + 0.5, abs.getY() + 1.0, abs.getZ() + 0.5);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(abs), Direction.UP, abs, false);
        var evt = new PlayerInteractEvent.RightClickBlock(p, InteractionHand.MAIN_HAND, abs, hit);
        NeoForge.EVENT_BUS.post(evt);
        return evt.isCanceled();
    }

    private static UUID U = UUID.randomUUID();

    private static void denied(GameTestHelper h, BlockPos a, StateFlag f, String name) {
        h.assertTrue(!mgr(h).testState(f, U, a.getX(), a.getY(), a.getZ()), name + " deny → testState false");
    }
    private static void allowed(GameTestHelper h, BlockPos a, StateFlag f, String name) {
        h.assertTrue(mgr(h).testState(f, U, a.getX(), a.getY(), a.getZ()), name + " allow → testState true");
    }
    private static <T> void value(GameTestHelper h, BlockPos a, Flag<T> f, T expected, String name) {
        T v = mgr(h).resolveValue(f, a.getX(), a.getY(), a.getZ(), null);
        h.assertTrue(Objects.equals(expected, v), name + " resolves (got " + v + ")");
    }

    /* ---------------- ENVIRONMENTAL DAMAGE (LivingIncomingDamageEvent) ---------------- */

    @GameTest(template = TPL)
    public static void fallDamageDenyCancels(GameTestHelper h) {
        region(h, "gt_fall").setFlag(Flags.FALL_DAMAGE, StateFlag.State.DENY);
        h.assertTrue(damageGateCancels(h, new BlockPos(4, 1, 4), h.getLevel().damageSources().fall()),
                "fall-damage deny → gate cancels");
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void fallDamageDefaultAllows(GameTestHelper h) {
        region(h, "gt_fall_def"); // no flag → vanilla fall damage proceeds
        h.assertTrue(!damageGateCancels(h, new BlockPos(4, 1, 4), h.getLevel().damageSources().fall()),
                "fall-damage unset → gate does not cancel");
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void fireDamageDenyCancels(GameTestHelper h) {
        region(h, "gt_fire_dmg").setFlag(Flags.FIRE_DAMAGE, StateFlag.State.DENY);
        h.assertTrue(damageGateCancels(h, new BlockPos(4, 1, 4), h.getLevel().damageSources().inFire()),
                "fire-damage deny → gate cancels");
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void drownDamageDenyCancels(GameTestHelper h) {
        region(h, "gt_drown").setFlag(Flags.DROWN_DAMAGE, StateFlag.State.DENY);
        h.assertTrue(damageGateCancels(h, new BlockPos(4, 1, 4), h.getLevel().damageSources().drown()),
                "drown-damage deny → gate cancels");
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void suffocationDamageDenyCancels(GameTestHelper h) {
        region(h, "gt_suffocate").setFlag(Flags.SUFFOCATION_DAMAGE, StateFlag.State.DENY);
        h.assertTrue(damageGateCancels(h, new BlockPos(4, 1, 4), h.getLevel().damageSources().inWall()),
                "suffocation-damage deny → gate cancels");
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void playerDamageDenyCancels(GameTestHelper h) {
        region(h, "gt_pdmg").setFlag(Flags.PLAYER_DAMAGE, StateFlag.State.DENY);
        h.assertTrue(damageGateCancels(h, new BlockPos(4, 1, 4), h.getLevel().damageSources().generic()),
                "player-damage deny → gate cancels generic damage");
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void invincibleAllowCancelsAllDamage(GameTestHelper h) {
        region(h, "gt_invinc").setFlag(Flags.INVINCIBLE, StateFlag.State.ALLOW);
        h.assertTrue(damageGateCancels(h, new BlockPos(4, 1, 4), h.getLevel().damageSources().generic()),
                "invincible → all damage cancelled");
        h.succeed();
    }

    /* ---------------- ENVIRONMENT MIXIN GATES (MixinFlagBridge#check) ---------------- */

    @GameTest(template = TPL)
    public static void grassSpreadDenyGate(GameTestHelper h) {
        region(h, "gt_grass").setFlag(Flags.GRASS_SPREAD, StateFlag.State.DENY);
        BlockPos a = h.absolutePos(new BlockPos(4, 1, 4));
        h.assertTrue(!MixinFlagBridge.check(h.getLevel(), a, Flags.GRASS_SPREAD), "grass-spread deny → gate blocks");
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void grassSpreadWildernessAllows(GameTestHelper h) {
        mgr(h);
        BlockPos far = h.absolutePos(new BlockPos(4, 1, 4)).offset(0, 200, 0);
        h.assertTrue(MixinFlagBridge.check(h.getLevel(), far, Flags.GRASS_SPREAD), "wilderness → grass-spread allowed");
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void myceliumSpreadDenyGate(GameTestHelper h) {
        region(h, "gt_myc").setFlag(Flags.MYCELIUM_SPREAD, StateFlag.State.DENY);
        BlockPos a = h.absolutePos(new BlockPos(4, 1, 4));
        h.assertTrue(!MixinFlagBridge.check(h.getLevel(), a, Flags.MYCELIUM_SPREAD), "mycelium-spread deny → gate blocks");
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void vineGrowthDenyGate(GameTestHelper h) {
        region(h, "gt_vine").setFlag(Flags.VINE_GROWTH, StateFlag.State.DENY);
        BlockPos a = h.absolutePos(new BlockPos(4, 1, 4));
        h.assertTrue(!MixinFlagBridge.check(h.getLevel(), a, Flags.VINE_GROWTH), "vine-growth deny → gate blocks");
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void iceFormDenyGate(GameTestHelper h) {
        region(h, "gt_iceform").setFlag(Flags.ICE_FORM, StateFlag.State.DENY);
        BlockPos a = h.absolutePos(new BlockPos(4, 1, 4));
        h.assertTrue(!MixinFlagBridge.check(h.getLevel(), a, Flags.ICE_FORM), "ice-form deny → gate blocks");
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void snowFallDenyGate(GameTestHelper h) {
        region(h, "gt_snowfall").setFlag(Flags.SNOW_FALL, StateFlag.State.DENY);
        BlockPos a = h.absolutePos(new BlockPos(4, 1, 4));
        h.assertTrue(!MixinFlagBridge.check(h.getLevel(), a, Flags.SNOW_FALL), "snow-fall deny → gate blocks");
        h.succeed();
    }

    /* ---------------- DEDICATED RIGHT-CLICK FLAGS (RightClickBlock gate) ---------------- */

    @GameTest(template = TPL)
    public static void signEditDenyCancels(GameTestHelper h) {
        CuboidRegion r = region(h, "gt_sign");
        r.setFlag(Flags.SIGN_EDIT, StateFlag.State.DENY);
        ServerPlayer p = memberOf(h, r); // member → isolates the dedicated sign-edit toggle
        BlockPos rel = new BlockPos(4, 1, 4);
        h.setBlock(rel, Blocks.OAK_SIGN);
        h.assertTrue(rightClickGateCancels(h, p, rel, ItemStack.EMPTY), "sign-edit deny → gate cancels");
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void lecternTakeDenyCancels(GameTestHelper h) {
        CuboidRegion r = region(h, "gt_lectern");
        r.setFlag(Flags.LECTERN_TAKE, StateFlag.State.DENY);
        ServerPlayer p = memberOf(h, r);
        BlockPos rel = new BlockPos(4, 1, 4);
        h.setBlock(rel, Blocks.LECTERN.defaultBlockState()
                .setValue(net.minecraft.world.level.block.LecternBlock.HAS_BOOK, true));
        h.assertTrue(rightClickGateCancels(h, p, rel, ItemStack.EMPTY), "lectern-take deny → gate cancels");
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void bucketFillDenyCancels(GameTestHelper h) {
        CuboidRegion r = region(h, "gt_bfill");
        r.setFlag(Flags.BUCKET_FILL, StateFlag.State.DENY);
        ServerPlayer p = memberOf(h, r);
        // An EMPTY bucket → bucket-fill; the dedicated toggle is keyed off the held item.
        h.assertTrue(rightClickGateCancels(h, p, new BlockPos(4, 1, 4), new ItemStack(Items.BUCKET)),
                "bucket-fill deny → gate cancels");
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void bucketFillAllowForMember(GameTestHelper h) {
        CuboidRegion r = region(h, "gt_bfill_ok");
        r.setFlag(Flags.BUCKET_FILL, StateFlag.State.ALLOW);
        ServerPlayer p = memberOf(h, r);
        h.assertTrue(!rightClickGateCancels(h, p, new BlockPos(4, 1, 4), new ItemStack(Items.BUCKET)),
                "bucket-fill allow → gate does not cancel");
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void vehiclePlaceDenyCancels(GameTestHelper h) {
        CuboidRegion r = region(h, "gt_vplace");
        r.setFlag(Flags.VEHICLE_PLACE, StateFlag.State.DENY);
        ServerPlayer p = memberOf(h, r);
        h.assertTrue(rightClickGateCancels(h, p, new BlockPos(4, 1, 4), new ItemStack(Items.MINECART)),
                "vehicle-place deny → gate cancels");
        h.succeed();
    }

    /* ---------------- VALUE / SESSION FLAGS (resolveValue) ---------------- */

    @GameTest(template = TPL)
    public static void greetingFarewellResolve(GameTestHelper h) {
        CuboidRegion r = region(h, "gt_greet");
        r.setFlag(Flags.GREETING, "Welcome!");
        r.setFlag(Flags.FAREWELL, "Bye!");
        BlockPos a = h.absolutePos(new BlockPos(4, 2, 4));
        value(h, a, Flags.GREETING, "Welcome!", "greeting");
        value(h, a, Flags.FAREWELL, "Bye!", "farewell");
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void titleFlagsResolve(GameTestHelper h) {
        CuboidRegion r = region(h, "gt_titles");
        r.setFlag(Flags.GREETING_TITLE, "Hi");
        r.setFlag(Flags.FAREWELL_TITLE, "Cya");
        BlockPos a = h.absolutePos(new BlockPos(4, 2, 4));
        value(h, a, Flags.GREETING_TITLE, "Hi", "greeting-title");
        value(h, a, Flags.FAREWELL_TITLE, "Cya", "farewell-title");
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void denyMessageFlagsResolve(GameTestHelper h) {
        CuboidRegion r = region(h, "gt_denymsg");
        r.setFlag(Flags.DENY_MESSAGE, "No.");
        r.setFlag(Flags.ENTRY_DENY_MESSAGE, "No entry.");
        r.setFlag(Flags.EXIT_DENY_MESSAGE, "No exit.");
        BlockPos a = h.absolutePos(new BlockPos(4, 2, 4));
        value(h, a, Flags.DENY_MESSAGE, "No.", "deny-message");
        value(h, a, Flags.ENTRY_DENY_MESSAGE, "No entry.", "entry-deny-message");
        value(h, a, Flags.EXIT_DENY_MESSAGE, "No exit.", "exit-deny-message");
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void healFlagsResolve(GameTestHelper h) {
        CuboidRegion r = region(h, "gt_heal");
        r.setFlag(Flags.HEAL_DELAY, 40);
        r.setFlag(Flags.HEAL_AMOUNT, 2);
        r.setFlag(Flags.MAX_HEAL, 20);
        r.setFlag(Flags.MIN_HEAL, 1);
        BlockPos a = h.absolutePos(new BlockPos(4, 2, 4));
        value(h, a, Flags.HEAL_DELAY, 40, "heal-delay");
        value(h, a, Flags.HEAL_AMOUNT, 2, "heal-amount");
        value(h, a, Flags.MAX_HEAL, 20, "heal-max");
        value(h, a, Flags.MIN_HEAL, 1, "heal-min");
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void feedFlagsResolve(GameTestHelper h) {
        CuboidRegion r = region(h, "gt_feed");
        r.setFlag(Flags.FEED_DELAY, 60);
        r.setFlag(Flags.FEED_AMOUNT, 3);
        r.setFlag(Flags.FEED_MAX, 20);
        r.setFlag(Flags.FEED_MIN, 4);
        BlockPos a = h.absolutePos(new BlockPos(4, 2, 4));
        value(h, a, Flags.FEED_DELAY, 60, "feed-delay");
        value(h, a, Flags.FEED_AMOUNT, 3, "feed-amount");
        value(h, a, Flags.FEED_MAX, 20, "feed-max");
        value(h, a, Flags.FEED_MIN, 4, "feed-min");
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void gameModeAndLocksResolve(GameTestHelper h) {
        CuboidRegion r = region(h, "gt_locks");
        r.setFlag(Flags.GAME_MODE, "adventure");
        r.setFlag(Flags.TIME_LOCK, "night");
        r.setFlag(Flags.WEATHER_LOCK, "clear");
        BlockPos a = h.absolutePos(new BlockPos(4, 2, 4));
        value(h, a, Flags.GAME_MODE, "adventure", "game-mode");
        value(h, a, Flags.TIME_LOCK, "night", "time-lock");
        value(h, a, Flags.WEATHER_LOCK, "clear", "weather-lock");
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void maxSpeedAndNotifyResolve(GameTestHelper h) {
        CuboidRegion r = region(h, "gt_speed");
        r.setFlag(Flags.MAX_SPEED, 0.35);
        r.setFlag(Flags.NOTIFY_ENTER, true);
        r.setFlag(Flags.NOTIFY_LEAVE, false);
        BlockPos a = h.absolutePos(new BlockPos(4, 2, 4));
        value(h, a, Flags.MAX_SPEED, 0.35, "max-speed");
        value(h, a, Flags.NOTIFY_ENTER, true, "notify-enter");
        value(h, a, Flags.NOTIFY_LEAVE, false, "notify-leave");
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void commandAndLocationFlagsResolve(GameTestHelper h) {
        CuboidRegion r = region(h, "gt_cmdloc");
        r.setFlag(Flags.ON_ENTRY, "say hi");
        r.setFlag(Flags.ON_EXIT, "say bye");
        r.setFlag(Flags.SPAWN_LOC, "0 64 0");
        r.setFlag(Flags.TELE_LOC, "10 64 10");
        BlockPos a = h.absolutePos(new BlockPos(4, 2, 4));
        value(h, a, Flags.ON_ENTRY, "say hi", "on-entry");
        value(h, a, Flags.ON_EXIT, "say bye", "on-exit");
        value(h, a, Flags.SPAWN_LOC, "0 64 0", "spawn");
        value(h, a, Flags.TELE_LOC, "10 64 10", "teleport");
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void setFlagsResolve(GameTestHelper h) {
        CuboidRegion r = region(h, "gt_sets");
        r.setFlag(Flags.BLOCKED_CMDS, Set.of("tp", "gamemode"));
        r.setFlag(Flags.ALLOWED_CMDS, Set.of("help"));
        r.setFlag(Flags.DENY_SPAWN, Set.of("minecraft:zombie"));
        r.setFlag(Flags.SPAWN_LIMIT, Set.of("minecraft:cow:5"));
        r.setFlag(Flags.BLOCKED_EFFECTS, Set.of("minecraft:poison"));
        BlockPos a = h.absolutePos(new BlockPos(4, 2, 4));
        value(h, a, Flags.BLOCKED_CMDS, Set.of("tp", "gamemode"), "blocked-cmds");
        value(h, a, Flags.ALLOWED_CMDS, Set.of("help"), "allowed-cmds");
        value(h, a, Flags.DENY_SPAWN, Set.of("minecraft:zombie"), "deny-spawn");
        value(h, a, Flags.SPAWN_LIMIT, Set.of("minecraft:cow:5"), "spawn-limit");
        value(h, a, Flags.BLOCKED_EFFECTS, Set.of("minecraft:poison"), "blocked-effects");
        h.succeed();
    }

    /* ---------------- REMAINING STATE FLAGS (testState) ---------------- */

    @GameTest(template = TPL)
    public static void movementStateFlagsResolve(GameTestHelper h) {
        CuboidRegion r = region(h, "gt_move");
        r.setFlag(Flags.ENTRY, StateFlag.State.DENY);
        r.setFlag(Flags.EXIT, StateFlag.State.DENY);
        r.setFlag(Flags.ENTRY_VEHICLE, StateFlag.State.DENY);
        r.setFlag(Flags.EXIT_VEHICLE, StateFlag.State.DENY);
        r.setFlag(Flags.SLEEP, StateFlag.State.DENY);
        r.setFlag(Flags.GLIDE, StateFlag.State.DENY);
        BlockPos a = h.absolutePos(new BlockPos(4, 2, 4));
        denied(h, a, Flags.ENTRY, "entry");
        denied(h, a, Flags.EXIT, "exit");
        denied(h, a, Flags.ENTRY_VEHICLE, "entry-vehicle");
        denied(h, a, Flags.EXIT_VEHICLE, "exit-vehicle");
        denied(h, a, Flags.SLEEP, "sleep");
        denied(h, a, Flags.GLIDE, "glide");
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void itemChatStateFlagsResolve(GameTestHelper h) {
        CuboidRegion r = region(h, "gt_itemchat");
        r.setFlag(Flags.ITEM_PICKUP, StateFlag.State.DENY);
        r.setFlag(Flags.ITEM_DROP, StateFlag.State.DENY);
        r.setFlag(Flags.SEND_CHAT, StateFlag.State.DENY);
        r.setFlag(Flags.RECEIVE_CHAT, StateFlag.State.DENY);
        r.setFlag(Flags.HUNGER_DRAIN, StateFlag.State.DENY);
        BlockPos a = h.absolutePos(new BlockPos(4, 2, 4));
        denied(h, a, Flags.ITEM_PICKUP, "item-pickup");
        denied(h, a, Flags.ITEM_DROP, "item-drop");
        denied(h, a, Flags.SEND_CHAT, "send-chat");
        denied(h, a, Flags.RECEIVE_CHAT, "receive-chat");
        denied(h, a, Flags.HUNGER_DRAIN, "hunger-drain");
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void deathStateFlagsResolve(GameTestHelper h) {
        CuboidRegion r = region(h, "gt_death");
        r.setFlag(Flags.KEEP_INVENTORY, StateFlag.State.ALLOW);
        r.setFlag(Flags.KEEP_XP, StateFlag.State.ALLOW);
        r.setFlag(Flags.EXP_DROPS, StateFlag.State.DENY);
        BlockPos a = h.absolutePos(new BlockPos(4, 2, 4));
        allowed(h, a, Flags.KEEP_INVENTORY, "keep-inventory");
        allowed(h, a, Flags.KEEP_XP, "keep-xp");
        denied(h, a, Flags.EXP_DROPS, "exp-drops");
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void physicsStateFlagsResolve(GameTestHelper h) {
        CuboidRegion r = region(h, "gt_physics");
        r.setFlag(Flags.REDSTONE, StateFlag.State.DENY);
        r.setFlag(Flags.PISTONS, StateFlag.State.DENY);
        r.setFlag(Flags.DISPENSER_OUTPUT, StateFlag.State.DENY);
        r.setFlag(Flags.WATER_FLOW, StateFlag.State.DENY);
        r.setFlag(Flags.LAVA_FLOW, StateFlag.State.DENY);
        r.setFlag(Flags.FIRE_SPREAD, StateFlag.State.DENY);
        r.setFlag(Flags.LAVA_FIRE, StateFlag.State.DENY);
        BlockPos a = h.absolutePos(new BlockPos(4, 2, 4));
        denied(h, a, Flags.REDSTONE, "redstone");
        denied(h, a, Flags.PISTONS, "pistons");
        denied(h, a, Flags.DISPENSER_OUTPUT, "dispenser-output");
        denied(h, a, Flags.WATER_FLOW, "water-flow");
        denied(h, a, Flags.LAVA_FLOW, "lava-flow");
        denied(h, a, Flags.FIRE_SPREAD, "fire-spread");
        denied(h, a, Flags.LAVA_FIRE, "lava-fire");
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void mobStateFlagsResolve(GameTestHelper h) {
        CuboidRegion r = region(h, "gt_mobstate");
        r.setFlag(Flags.MOB_SPAWNING, StateFlag.State.DENY);
        r.setFlag(Flags.MOB_GRIEF, StateFlag.State.DENY);
        r.setFlag(Flags.MOB_TELEPORT, StateFlag.State.DENY);
        r.setFlag(Flags.ENDER_BUILD, StateFlag.State.DENY);
        r.setFlag(Flags.CHORUS_FRUIT, StateFlag.State.DENY);
        BlockPos a = h.absolutePos(new BlockPos(4, 2, 4));
        denied(h, a, Flags.MOB_SPAWNING, "mob-spawning");
        denied(h, a, Flags.MOB_GRIEF, "mob-grief");
        denied(h, a, Flags.MOB_TELEPORT, "mob-teleport");
        denied(h, a, Flags.ENDER_BUILD, "enderpearl");
        denied(h, a, Flags.CHORUS_FRUIT, "chorus-teleport");
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void explosionStateFlagsResolve(GameTestHelper h) {
        CuboidRegion r = region(h, "gt_expl");
        r.setFlag(Flags.TNT, StateFlag.State.DENY);
        r.setFlag(Flags.CREEPER_EXPLOSION, StateFlag.State.DENY);
        r.setFlag(Flags.GHAST_FIREBALL, StateFlag.State.DENY);
        r.setFlag(Flags.ENDERDRAGON, StateFlag.State.DENY);
        r.setFlag(Flags.OTHER_EXPLOSION, StateFlag.State.DENY);
        BlockPos a = h.absolutePos(new BlockPos(4, 2, 4));
        denied(h, a, Flags.TNT, "tnt");
        denied(h, a, Flags.CREEPER_EXPLOSION, "creeper-explosion");
        denied(h, a, Flags.GHAST_FIREBALL, "ghast-fireball");
        denied(h, a, Flags.ENDERDRAGON, "enderdragon");
        denied(h, a, Flags.OTHER_EXPLOSION, "other-explosion");
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void decorationStateFlagsResolve(GameTestHelper h) {
        CuboidRegion r = region(h, "gt_decostate");
        r.setFlag(Flags.ARMOR_STAND_USE, StateFlag.State.DENY);
        r.setFlag(Flags.ITEM_FRAME_ROTATE, StateFlag.State.DENY);
        r.setFlag(Flags.SIGN_EDIT, StateFlag.State.DENY);
        r.setFlag(Flags.LECTERN_TAKE, StateFlag.State.DENY);
        r.setFlag(Flags.BUCKET_FILL, StateFlag.State.DENY);
        r.setFlag(Flags.VEHICLE_PLACE, StateFlag.State.DENY);
        BlockPos a = h.absolutePos(new BlockPos(4, 2, 4));
        denied(h, a, Flags.ARMOR_STAND_USE, "armor-stand-use");
        denied(h, a, Flags.ITEM_FRAME_ROTATE, "item-frame-rotate");
        denied(h, a, Flags.SIGN_EDIT, "sign-edit");
        denied(h, a, Flags.LECTERN_TAKE, "lectern-take");
        denied(h, a, Flags.BUCKET_FILL, "bucket-fill");
        denied(h, a, Flags.VEHICLE_PLACE, "vehicle-place");
        h.succeed();
    }

    /* ---------------- ENGINE SEMANTICS ---------------- */

    @GameTest(template = TPL)
    public static void priorityHigherWins(GameTestHelper h) {
        CuboidRegion lo = region(h, "gt_prio_lo"); lo.setPriority(0);  lo.setFlag(Flags.PVP, StateFlag.State.ALLOW);
        CuboidRegion hi = region(h, "gt_prio_hi"); hi.setPriority(10); hi.setFlag(Flags.PVP, StateFlag.State.DENY);
        BlockPos a = h.absolutePos(new BlockPos(4, 2, 4));
        denied(h, a, Flags.PVP, "higher-priority DENY overrides lower ALLOW");
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void denyBeatsAllowEqualPriority(GameTestHelper h) {
        CuboidRegion x = region(h, "gt_eq_a"); x.setPriority(5); x.setFlag(Flags.PVP, StateFlag.State.ALLOW);
        CuboidRegion y = region(h, "gt_eq_b"); y.setPriority(5); y.setFlag(Flags.PVP, StateFlag.State.DENY);
        BlockPos a = h.absolutePos(new BlockPos(4, 2, 4));
        denied(h, a, Flags.PVP, "DENY beats ALLOW at equal priority");
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void parentInheritanceState(GameTestHelper h) {
        // Parent does NOT contain the query point; the child inherits via the parent pointer.
        CuboidRegion parent = makeRegion(h, "gt_pinh_parent", new BlockPos(0, 0, 0), new BlockPos(1, 1, 1));
        parent.setFlag(Flags.PVP, StateFlag.State.DENY);
        CuboidRegion child = region(h, "gt_pinh_child");
        child.setParent(parent);
        BlockPos a = h.absolutePos(new BlockPos(4, 2, 4)); // inside child, outside parent
        denied(h, a, Flags.PVP, "child inherits parent's PVP DENY");
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void parentInheritanceValue(GameTestHelper h) {
        CuboidRegion parent = makeRegion(h, "gt_pival_parent", new BlockPos(0, 0, 0), new BlockPos(1, 1, 1));
        parent.setFlag(Flags.GREETING, "FromParent");
        CuboidRegion child = region(h, "gt_pival_child");
        child.setParent(parent);
        BlockPos a = h.absolutePos(new BlockPos(4, 2, 4));
        value(h, a, Flags.GREETING, "FromParent", "child inherits parent greeting");
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void groupFilterMembersOnly(GameTestHelper h) {
        CuboidRegion r = region(h, "gt_grp_mem");
        r.setFlag(Flags.PVP, StateFlag.State.DENY);
        r.setFlagGroup(Flags.PVP, RegionGroup.MEMBERS);
        UUID member = UUID.randomUUID();
        r.members().add(member);
        UUID stranger = UUID.randomUUID();
        BlockPos a = h.absolutePos(new BlockPos(4, 2, 4));
        h.assertTrue(!mgr(h).testState(Flags.PVP, member, a.getX(), a.getY(), a.getZ()),
                "MEMBERS-group DENY applies to a member");
        h.assertTrue(mgr(h).testState(Flags.PVP, stranger, a.getX(), a.getY(), a.getZ()),
                "MEMBERS-group DENY does NOT apply to a stranger (defaults allow)");
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void groupFilterNonMembersOnly(GameTestHelper h) {
        CuboidRegion r = region(h, "gt_grp_non");
        r.setFlag(Flags.PVP, StateFlag.State.DENY);
        r.setFlagGroup(Flags.PVP, RegionGroup.NON_MEMBERS);
        UUID member = UUID.randomUUID();
        r.members().add(member);
        UUID stranger = UUID.randomUUID();
        BlockPos a = h.absolutePos(new BlockPos(4, 2, 4));
        h.assertTrue(mgr(h).testState(Flags.PVP, member, a.getX(), a.getY(), a.getZ()),
                "NON_MEMBERS-group DENY does NOT apply to a member");
        h.assertTrue(!mgr(h).testState(Flags.PVP, stranger, a.getX(), a.getY(), a.getZ()),
                "NON_MEMBERS-group DENY applies to a stranger");
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void wildernessUsesFlagDefaults(GameTestHelper h) {
        mgr(h); // ensure manager; query a point far outside any region
        BlockPos far = h.absolutePos(new BlockPos(4, 2, 4)).offset(0, 200, 0);
        h.assertTrue(mgr(h).testState(Flags.PVP, U, far.getX(), far.getY(), far.getZ()),
                "wilderness PVP → default allow");
        h.assertTrue(!mgr(h).testState(Flags.KEEP_INVENTORY, U, far.getX(), far.getY(), far.getZ()),
                "wilderness keep-inventory → default deny");
        h.assertTrue(mgr(h).resolveValue(Flags.GREETING, far.getX(), far.getY(), far.getZ(), null) == null,
                "wilderness unset value flag → null");
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void testBuildAccessImplicitMembership(GameTestHelper h) {
        CuboidRegion r = region(h, "gt_implicit"); // no build flags set
        UUID member = UUID.randomUUID();
        r.members().add(member);
        BlockPos a = h.absolutePos(new BlockPos(4, 2, 4));
        h.assertTrue(mgr(h).testBuildAccess(Flags.BUILD, a.getX(), a.getY(), a.getZ(), member),
                "member builds in an unflagged region");
        h.assertTrue(!mgr(h).testBuildAccess(Flags.BUILD, a.getX(), a.getY(), a.getZ(), UUID.randomUUID()),
                "stranger cannot build in an unflagged region");
        h.assertTrue(!mgr(h).testBuildAccess(Flags.BUILD, a.getX(), a.getY(), a.getZ(), null),
                "null actor cannot build in an unflagged region");
        h.succeed();
    }

    /* ============================================================================
     *  GENERATED MATRIX — every registered flag exercised under every resolution
     *  scenario. @GameTestGenerator emits one TestFunction per (flag × scenario),
     *  scaling the battery into the hundreds so the entire flag surface of the mod
     *  is verified: deny / allow / wilderness-default / priority / equal-priority
     *  deny-beats-allow / parent inheritance / four region-group filters for state
     *  flags, and set / parent / wilderness / group for value flags.
     * ========================================================================== */

    private static BlockPos P(GameTestHelper h)    { return h.absolutePos(new BlockPos(4, 2, 4)); }
    private static BlockPos WILD(GameTestHelper h) { return h.absolutePos(new BlockPos(4, 2, 4)).offset(0, 200, 0); }

    private static TestFunction raw(String name, Consumer<GameTestHelper> fn) {
        // batchName is reassigned in chunks by generated(); structureName MUST be namespaced
        // "worldguardneo:" — the runner filters generated tests by the structure's namespace.
        return new TestFunction("wgn_tmp", "worldguardneo:" + name, "worldguardneo:platform",
                200, 0L, true, fn);
    }

    @GameTestGenerator
    public static Collection<TestFunction> generated() {
        List<TestFunction> tmp = new ArrayList<>();
        for (Flag<?> flag : Flags.all()) {
            if (flag instanceof StateFlag sf) addStateScenarios(tmp, sf);
            else                              addValueScenarios(tmp, flag);
        }
        // Chunk into small batches so the framework never places hundreds of test arenas at once
        // (one big batch would balloon memory / chunk-gen time on the headless server).
        List<TestFunction> out = new ArrayList<>(tmp.size());
        for (int i = 0; i < tmp.size(); i++) {
            TestFunction t = tmp.get(i);
            out.add(new TestFunction("wgn_gen_" + (i / 48), t.testName(), t.structureName(),
                    t.maxTicks(), t.setupTicks(), t.required(), t.function()));
        }
        return out;
    }

    private static void addStateScenarios(List<TestFunction> out, StateFlag f) {
        String n = f.name().replace(':', '_');
        boolean def = f.defaultAllow();

        out.add(raw("state_deny__" + n, h -> {
            region(h, "gd_" + n).setFlag(f, StateFlag.State.DENY);
            denied(h, P(h), f, n); h.succeed();
        }));
        out.add(raw("state_allow__" + n, h -> {
            region(h, "ga_" + n).setFlag(f, StateFlag.State.ALLOW);
            allowed(h, P(h), f, n); h.succeed();
        }));
        out.add(raw("state_wilderness__" + n, h -> {
            mgr(h); BlockPos w = WILD(h);
            h.assertTrue(mgr(h).testState(f, U, w.getX(), w.getY(), w.getZ()) == def,
                    n + " wilderness → flag default (" + def + ")");
            h.succeed();
        }));
        out.add(raw("state_priority__" + n, h -> {
            CuboidRegion lo = region(h, "gpl_" + n); lo.setPriority(0);  lo.setFlag(f, StateFlag.State.ALLOW);
            CuboidRegion hi = region(h, "gph_" + n); hi.setPriority(10); hi.setFlag(f, StateFlag.State.DENY);
            denied(h, P(h), f, n + " higher-priority deny");
            h.succeed();
        }));
        out.add(raw("state_eqprio__" + n, h -> {
            CuboidRegion a = region(h, "gea_" + n); a.setPriority(5); a.setFlag(f, StateFlag.State.ALLOW);
            CuboidRegion b = region(h, "geb_" + n); b.setPriority(5); b.setFlag(f, StateFlag.State.DENY);
            denied(h, P(h), f, n + " deny-beats-allow");
            h.succeed();
        }));
        out.add(raw("state_parent__" + n, h -> {
            CuboidRegion parent = makeRegion(h, "gpp_" + n, new BlockPos(0, 0, 0), new BlockPos(1, 1, 1));
            parent.setFlag(f, StateFlag.State.DENY);
            region(h, "gpc_" + n).setParent(parent);
            denied(h, P(h), f, n + " inherits parent deny"); // P=(4,2,4) outside parent, inside child
            h.succeed();
        }));
        out.add(raw("state_group_members__" + n, h -> {
            CuboidRegion r = region(h, "ggm_" + n);
            r.setFlag(f, StateFlag.State.DENY); r.setFlagGroup(f, RegionGroup.MEMBERS);
            UUID m = UUID.randomUUID(); r.members().add(m); UUID s = UUID.randomUUID();
            BlockPos a = P(h);
            h.assertTrue(!mgr(h).testState(f, m, a.getX(), a.getY(), a.getZ()), n + " members-deny hits member");
            h.assertTrue(mgr(h).testState(f, s, a.getX(), a.getY(), a.getZ()) == def, n + " members-deny spares stranger");
            h.succeed();
        }));
        out.add(raw("state_group_nonmembers__" + n, h -> {
            CuboidRegion r = region(h, "ggn_" + n);
            r.setFlag(f, StateFlag.State.DENY); r.setFlagGroup(f, RegionGroup.NON_MEMBERS);
            UUID m = UUID.randomUUID(); r.members().add(m); UUID s = UUID.randomUUID();
            BlockPos a = P(h);
            h.assertTrue(!mgr(h).testState(f, s, a.getX(), a.getY(), a.getZ()), n + " non-members-deny hits stranger");
            h.assertTrue(mgr(h).testState(f, m, a.getX(), a.getY(), a.getZ()) == def, n + " non-members-deny spares member");
            h.succeed();
        }));
        out.add(raw("state_group_owners__" + n, h -> {
            CuboidRegion r = region(h, "ggo_" + n);
            r.setFlag(f, StateFlag.State.DENY); r.setFlagGroup(f, RegionGroup.OWNERS);
            UUID o = UUID.randomUUID(); r.owners().add(o); UUID s = UUID.randomUUID();
            BlockPos a = P(h);
            h.assertTrue(!mgr(h).testState(f, o, a.getX(), a.getY(), a.getZ()), n + " owners-deny hits owner");
            h.assertTrue(mgr(h).testState(f, s, a.getX(), a.getY(), a.getZ()) == def, n + " owners-deny spares stranger");
            h.succeed();
        }));
        out.add(raw("state_group_nonowners__" + n, h -> {
            CuboidRegion r = region(h, "ggno_" + n);
            r.setFlag(f, StateFlag.State.DENY); r.setFlagGroup(f, RegionGroup.NON_OWNERS);
            UUID o = UUID.randomUUID(); r.owners().add(o); UUID s = UUID.randomUUID();
            BlockPos a = P(h);
            h.assertTrue(!mgr(h).testState(f, s, a.getX(), a.getY(), a.getZ()), n + " non-owners-deny hits stranger");
            h.assertTrue(mgr(h).testState(f, o, a.getX(), a.getY(), a.getZ()) == def, n + " non-owners-deny spares owner");
            h.succeed();
        }));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void setFlagRaw(ProtectedRegion r, Flag<?> f, Object v) { r.setFlag((Flag) f, v); }

    private static Object sampleValue(Flag<?> f) {
        if (f instanceof StringFlag)  return "wgn_test";
        if (f instanceof IntegerFlag) return 7;
        if (f instanceof DoubleFlag)  return 1.5;
        if (f instanceof BooleanFlag) return Boolean.TRUE;
        if (f instanceof SetFlag)     return Set.of("alpha", "beta");
        return null;
    }

    private static void addValueScenarios(List<TestFunction> out, Flag<?> f) {
        Object v = sampleValue(f);
        if (v == null) return; // unknown value type — nothing to assert generically
        String n = f.name().replace(':', '_');

        out.add(raw("value_set__" + n, h -> {
            CuboidRegion r = region(h, "vs_" + n); setFlagRaw(r, f, v);
            BlockPos a = P(h);
            Object got = mgr(h).resolveValue(f, a.getX(), a.getY(), a.getZ(), null);
            h.assertTrue(Objects.equals(v, got), n + " value resolves (got " + got + ")");
            h.succeed();
        }));
        out.add(raw("value_parent__" + n, h -> {
            CuboidRegion parent = makeRegion(h, "vpp_" + n, new BlockPos(0, 0, 0), new BlockPos(1, 1, 1));
            setFlagRaw(parent, f, v);
            region(h, "vpc_" + n).setParent(parent);
            BlockPos a = P(h);
            Object got = mgr(h).resolveValue(f, a.getX(), a.getY(), a.getZ(), null);
            h.assertTrue(Objects.equals(v, got), n + " value inherited from parent (got " + got + ")");
            h.succeed();
        }));
        out.add(raw("value_wilderness__" + n, h -> {
            mgr(h); BlockPos w = WILD(h);
            Object got = mgr(h).resolveValue(f, w.getX(), w.getY(), w.getZ(), null);
            h.assertTrue(got == null, n + " unset in wilderness → null (got " + got + ")");
            h.succeed();
        }));
    }

    /* ============================================================================
     *  PHYSICAL GRIEF — real explosions through the live ExplosionEvent path.
     *  level.explode() computes the affected blocks, fires ExplosionEvent.Detonate
     *  (where the mod removes protected blocks), then destroys what remains — so a
     *  protected region's blocks must physically survive the blast.
     * ========================================================================== */

    @GameTest(template = TPL)
    public static void explosionWildernessDestroysStone(GameTestHelper h) {
        mgr(h); // no region → vanilla blast destroys the block (control proving the test works)
        BlockPos rel = new BlockPos(4, 1, 4);
        h.setBlock(rel, Blocks.STONE);
        BlockPos c = h.absolutePos(rel);
        h.getLevel().explode(null, c.getX() + 0.5, c.getY() + 0.5, c.getZ() + 0.5, 3.0f,
                Level.ExplosionInteraction.TNT);
        h.assertBlockNotPresent(Blocks.STONE, rel);
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void explosionOtherGriefDenied(GameTestHelper h) {
        region(h, "gt_expl_other").setFlag(Flags.OTHER_EXPLOSION, StateFlag.State.DENY);
        BlockPos rel = new BlockPos(4, 1, 4);
        h.setBlock(rel, Blocks.STONE);
        BlockPos c = h.absolutePos(rel);
        // null source → OTHER_EXPLOSION; deny → the mod drops the block from the blast.
        h.getLevel().explode(null, c.getX() + 0.5, c.getY() + 0.5, c.getZ() + 0.5, 3.0f,
                Level.ExplosionInteraction.TNT);
        h.assertBlockPresent(Blocks.STONE, rel);
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void explosionTntGriefDenied(GameTestHelper h) {
        region(h, "gt_expl_tnt").setFlag(Flags.TNT, StateFlag.State.DENY);
        BlockPos rel = new BlockPos(4, 1, 4);
        h.setBlock(rel, Blocks.STONE);
        BlockPos c = h.absolutePos(rel);
        PrimedTnt tnt = new PrimedTnt(h.getLevel(), c.getX() + 0.5, c.getY() + 0.5, c.getZ() + 0.5, null);
        h.getLevel().explode(tnt, c.getX() + 0.5, c.getY() + 0.5, c.getZ() + 0.5, 3.0f,
                Level.ExplosionInteraction.TNT);
        h.assertBlockPresent(Blocks.STONE, rel); // TNT source → tnt flag deny → survives
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void explosionCreeperGriefDenied(GameTestHelper h) {
        region(h, "gt_expl_creeper").setFlag(Flags.CREEPER_EXPLOSION, StateFlag.State.DENY);
        BlockPos rel = new BlockPos(4, 1, 4);
        h.setBlock(rel, Blocks.STONE);
        Creeper creeper = h.spawn(EntityType.CREEPER, new BlockPos(1, 1, 1));
        BlockPos c = h.absolutePos(rel);
        h.getLevel().explode(creeper, c.getX() + 0.5, c.getY() + 0.5, c.getZ() + 0.5, 3.0f,
                Level.ExplosionInteraction.MOB);
        h.assertBlockPresent(Blocks.STONE, rel); // creeper source → creeper-explosion deny → survives
        h.succeed();
    }

    /* ============================================================================
     *  ENGINE / API / GEOMETRY / MEMBERSHIP / CODEC — verifying the mod's functions
     *  beyond flag enforcement: the region store, spatial queries, cuboid geometry,
     *  ownership, flag (de)serialization, and the public WorldGuardNeoAPI facade.
     *  Pure logic against the live RegionManager — deterministic in the test server.
     * ========================================================================== */

    private static dev.thefather007.worldguardneo.util.Vec3 v(BlockPos p) {
        return new dev.thefather007.worldguardneo.util.Vec3(p.getX(), p.getY(), p.getZ());
    }

    // ---- region store ----

    @GameTest(template = TPL)
    public static void engineAddGetRemove(GameTestHelper h) {
        RegionManager m = mgr(h);
        m.remove("eng_agr");
        int before = m.size();
        CuboidRegion r = makeRegion(h, "eng_agr", new BlockPos(0, 0, 0), new BlockPos(2, 2, 2));
        h.assertTrue(m.get("eng_agr").isPresent(), "get returns the added region");
        h.assertTrue(m.size() == before + 1, "size grew by one");
        h.assertTrue(m.remove("eng_agr"), "remove returns true");
        h.assertTrue(m.get("eng_agr").isEmpty(), "get empty after remove");
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void engineDuplicateAddRejected(GameTestHelper h) {
        makeRegion(h, "eng_dup", new BlockPos(0, 0, 0), new BlockPos(1, 1, 1));
        CuboidRegion dup = new CuboidRegion("eng_dup", v(h.absolutePos(new BlockPos(0, 0, 0))),
                v(h.absolutePos(new BlockPos(1, 1, 1))));
        h.assertTrue(!mgr(h).add(dup), "adding a duplicate id returns false");
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void engineRemoveUnlinksChild(GameTestHelper h) {
        CuboidRegion parent = makeRegion(h, "eng_p", new BlockPos(0, 0, 0), new BlockPos(1, 1, 1));
        CuboidRegion child = region(h, "eng_c");
        child.setParent(parent);
        mgr(h).remove("eng_p");
        h.assertTrue(child.parent() == null, "removing a parent unlinks the child");
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void engineGetApplicableSortedByPriority(GameTestHelper h) {
        CuboidRegion lo = region(h, "eng_lo"); lo.setPriority(1);
        CuboidRegion hi = region(h, "eng_hi"); hi.setPriority(9);
        BlockPos a = h.absolutePos(new BlockPos(4, 2, 4));
        List<ProtectedRegion> app = mgr(h).getApplicable(a.getX(), a.getY(), a.getZ());
        h.assertTrue(app.size() >= 2 && app.get(0) == hi, "highest priority sorts first");
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void engineWildernessEmptyAndHasAnyAt(GameTestHelper h) {
        region(h, "eng_w");
        BlockPos in = h.absolutePos(new BlockPos(4, 2, 4));
        BlockPos out = in.offset(0, 200, 0);
        h.assertTrue(mgr(h).hasAnyAt(in.getX(), in.getY(), in.getZ()), "hasAnyAt true inside");
        h.assertTrue(!mgr(h).hasAnyAt(out.getX(), out.getY(), out.getZ()), "hasAnyAt false in wilderness");
        h.assertTrue(mgr(h).getApplicable(out.getX(), out.getY(), out.getZ()).isEmpty(), "wilderness applicable empty");
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void engineOverlappingQuery(GameTestHelper h) {
        region(h, "eng_ov");
        List<ProtectedRegion> hit = mgr(h).overlapping(
                v(h.absolutePos(new BlockPos(3, 1, 3))), v(h.absolutePos(new BlockPos(5, 3, 5))));
        h.assertTrue(hit.stream().anyMatch(r -> r.id().equals("eng_ov")), "overlapping finds the region");
        List<ProtectedRegion> miss = mgr(h).overlapping(
                v(h.absolutePos(new BlockPos(4, 2, 4)).offset(0, 200, 0)),
                v(h.absolutePos(new BlockPos(4, 2, 4)).offset(2, 202, 2)));
        h.assertTrue(miss.stream().noneMatch(r -> r.id().equals("eng_ov")), "overlapping misses far box");
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void engineCrossesBoundary(GameTestHelper h) {
        region(h, "eng_cb");
        BlockPos in = h.absolutePos(new BlockPos(4, 2, 4));
        BlockPos out = in.offset(0, 200, 0);
        h.assertTrue(mgr(h).crossesBoundary(out.getX(), out.getY(), out.getZ(), in.getX(), in.getY(), in.getZ()),
                "wilderness → region crosses a boundary");
        h.assertTrue(!mgr(h).crossesBoundary(in.getX(), in.getY(), in.getZ(),
                in.getX() + 1, in.getY(), in.getZ()), "inside → inside does not cross");
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void engineOwnershipQueries(GameTestHelper h) {
        CuboidRegion r = region(h, "eng_own");
        UUID owner = UUID.randomUUID();
        r.owners().add(owner);
        BlockPos a = h.absolutePos(new BlockPos(4, 2, 4));
        h.assertTrue(mgr(h).isOwnerAt(owner, a.getX(), a.getY(), a.getZ()), "isOwnerAt true for owner");
        h.assertTrue(mgr(h).isMemberAt(owner, a.getX(), a.getY(), a.getZ()), "owner counts as member");
        h.assertTrue(mgr(h).countOwned(owner) >= 1, "countOwned >= 1");
        h.assertTrue(mgr(h).getOwnedBy(owner).stream().anyMatch(x -> x.id().equals("eng_own")), "getOwnedBy lists it");
        h.assertTrue(!mgr(h).isOwnerAt(UUID.randomUUID(), a.getX(), a.getY(), a.getZ()), "stranger not owner");
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void engineGlobalRegionNotApplicable(GameTestHelper h) {
        region(h, "eng_gl");
        BlockPos a = h.absolutePos(new BlockPos(4, 2, 4));
        h.assertTrue(mgr(h).getApplicable(a.getX(), a.getY(), a.getZ()).stream()
                .noneMatch(r -> r == mgr(h).globalRegion()), "global region is never in getApplicable");
        h.succeed();
    }

    // ---- cuboid geometry ----

    @GameTest(template = TPL)
    public static void cuboidContainsBounds(GameTestHelper h) {
        CuboidRegion r = makeRegion(h, "geo_c", new BlockPos(0, 0, 0), new BlockPos(2, 2, 2));
        BlockPos min = h.absolutePos(new BlockPos(0, 0, 0));
        BlockPos max = h.absolutePos(new BlockPos(2, 2, 2));
        h.assertTrue(r.contains(min.getX(), min.getY(), min.getZ()), "contains min corner");
        h.assertTrue(r.contains(max.getX(), max.getY(), max.getZ()), "contains max corner");
        h.assertTrue(!r.contains(max.getX() + 2, max.getY(), max.getZ()), "excludes far point");
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void cuboidNormalizesSwappedCorners(GameTestHelper h) {
        // pass corners in reversed order — region must still normalize and contain the middle
        CuboidRegion r = makeRegion(h, "geo_sw", new BlockPos(4, 3, 4), new BlockPos(0, 0, 0));
        BlockPos mid = h.absolutePos(new BlockPos(2, 1, 2));
        h.assertTrue(r.contains(mid.getX(), mid.getY(), mid.getZ()), "swapped corners normalize");
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void cuboidVolumeBoundsType(GameTestHelper h) {
        CuboidRegion r = makeRegion(h, "geo_v", new BlockPos(0, 0, 0), new BlockPos(2, 2, 2));
        h.assertTrue(r.volume() == 27L, "3x3x3 volume = 27 (got " + r.volume() + ")");
        h.assertTrue(r.type().equals("cuboid"), "type is cuboid");
        h.assertTrue(r.minimumBound() != null && r.maximumBound() != null, "bounds present");
        h.succeed();
    }

    // ---- membership / flags on a region ----

    @GameTest(template = TPL)
    public static void membershipOwnersAndMembers(GameTestHelper h) {
        CuboidRegion r = region(h, "mem_om");
        UUID o = UUID.randomUUID(), m = UUID.randomUUID(), s = UUID.randomUUID();
        r.owners().add(o);
        r.members().add(m);
        h.assertTrue(r.isOwner(o) && r.isMember(o), "owner is owner + member");
        h.assertTrue(!r.isOwner(m) && r.isMember(m), "member is member, not owner");
        h.assertTrue(!r.isOwner(s) && !r.isMember(s), "stranger is neither");
        h.assertTrue(r.ownersView().contains(o) && r.membersView().contains(m), "views reflect contents");
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void membershipGroups(GameTestHelper h) {
        CuboidRegion r = region(h, "mem_grp");
        r.ownerGroups().add("admins");
        r.memberGroups().add("trusted");
        h.assertTrue(r.ownerGroupsView().contains("admins"), "owner group recorded");
        h.assertTrue(r.memberGroupsView().contains("trusted"), "member group recorded");
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void flagSetGetUnset(GameTestHelper h) {
        CuboidRegion r = region(h, "flag_sgu");
        h.assertTrue(!r.hasFlags(), "fresh region has no flags");
        r.setFlag(Flags.PVP, StateFlag.State.DENY);
        h.assertTrue(r.getFlag(Flags.PVP) == StateFlag.State.DENY, "getFlag returns set value");
        h.assertTrue(r.hasFlags(), "hasFlags true after set");
        r.setFlag(Flags.PVP, null);
        h.assertTrue(r.getFlag(Flags.PVP) == null, "setting null unsets");
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void flagGroupSetGet(GameTestHelper h) {
        CuboidRegion r = region(h, "flag_grp");
        r.setFlag(Flags.PVP, StateFlag.State.DENY);
        r.setFlagGroup(Flags.PVP, RegionGroup.MEMBERS);
        h.assertTrue(r.getFlagGroup(Flags.PVP) == RegionGroup.MEMBERS, "flag group stored");
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void flagEpochBumpsOnChange(GameTestHelper h) {
        CuboidRegion r = region(h, "flag_epoch");
        long before = ProtectedRegion.flagEpoch();
        r.setFlag(Flags.PVP, StateFlag.State.DENY);
        h.assertTrue(ProtectedRegion.flagEpoch() > before, "flag epoch advances on mutation");
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void flagCopyFlagsFrom(GameTestHelper h) {
        CuboidRegion src = region(h, "flag_src");
        src.setFlag(Flags.PVP, StateFlag.State.DENY);
        src.setFlag(Flags.GREETING, "hello");
        CuboidRegion dst = makeRegion(h, "flag_dst", new BlockPos(0, 0, 0), new BlockPos(1, 1, 1));
        dst.copyFlagsFrom(src);
        h.assertTrue(dst.getFlag(Flags.PVP) == StateFlag.State.DENY, "copied state flag");
        h.assertTrue("hello".equals(dst.getFlag(Flags.GREETING)), "copied value flag");
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void parentCycleIsRejected(GameTestHelper h) {
        CuboidRegion a = region(h, "cyc_a");
        CuboidRegion b = makeRegion(h, "cyc_b", new BlockPos(0, 0, 0), new BlockPos(1, 1, 1));
        a.setParent(b);
        boolean threw = false;
        try { b.setParent(a); } catch (Exception e) { threw = true; } // cycle must be detected
        h.assertTrue(threw, "setParent rejects a parent cycle");
        h.assertTrue(b.parent() == null, "rejected cycle leaves the parent unset");
        h.succeed();
    }

    // ---- WorldGuardNeoAPI facade ----

    @GameTest(template = TPL)
    public static void apiRegionLookups(GameTestHelper h) {
        CuboidRegion r = region(h, "api_look");
        r.setPriority(3);
        BlockPos rel = new BlockPos(4, 2, 4);
        BlockPos abs = h.absolutePos(rel);
        var at = dev.thefather007.worldguardneo.api.WorldGuardNeoAPI.getRegionAt(h.getLevel(), abs);
        h.assertTrue(at.isPresent() && at.get().id().equals("api_look"), "getRegionAt finds region");
        h.assertTrue(dev.thefather007.worldguardneo.api.WorldGuardNeoAPI.getRegionsAt(h.getLevel(), abs).size() >= 1,
                "getRegionsAt non-empty");
        h.assertTrue(dev.thefather007.worldguardneo.api.WorldGuardNeoAPI.getRegion(h.getLevel(), "api_look").isPresent(),
                "getRegion by id");
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void apiBuildPermissionChecks(GameTestHelper h) {
        CuboidRegion r = region(h, "api_build");
        ServerPlayer member = combatantAt(h, new BlockPos(4, 1, 4));
        r.members().add(member.getUUID());
        ServerPlayer stranger = combatantAt(h, new BlockPos(4, 1, 4));
        BlockPos abs = h.absolutePos(new BlockPos(4, 1, 4));
        h.assertTrue(dev.thefather007.worldguardneo.api.WorldGuardNeoAPI.canBuild(member, abs),
                "member can build via API");
        h.assertTrue(!dev.thefather007.worldguardneo.api.WorldGuardNeoAPI.canBuild(stranger, abs),
                "stranger cannot build via API");
        h.assertTrue(!dev.thefather007.worldguardneo.api.WorldGuardNeoAPI.canInteract(stranger, abs),
                "stranger cannot interact via API");
        h.assertTrue(!dev.thefather007.worldguardneo.api.WorldGuardNeoAPI.canAccessChests(stranger, abs),
                "stranger cannot open chests via API");
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void apiQueryFlags(GameTestHelper h) {
        CuboidRegion r = region(h, "api_query");
        r.setFlag(Flags.PVP, StateFlag.State.DENY);
        r.setFlag(Flags.GREETING, "hi");
        BlockPos abs = h.absolutePos(new BlockPos(4, 2, 4));
        h.assertTrue(!dev.thefather007.worldguardneo.api.WorldGuardNeoAPI.queryFlag(
                h.getLevel(), Flags.PVP, null, abs), "queryFlag(PVP)=deny");
        var val = dev.thefather007.worldguardneo.api.WorldGuardNeoAPI.queryValue(
                h.getLevel(), Flags.GREETING, null, abs);
        h.assertTrue(val.isPresent() && val.get().equals("hi"), "queryValue(GREETING)");
        h.assertTrue(!dev.thefather007.worldguardneo.api.WorldGuardNeoAPI.queryFlag(
                h.getLevel(), "pvp", null, abs.getX(), abs.getY(), abs.getZ()), "queryFlag by name");
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void apiOwnershipAndBypass(GameTestHelper h) {
        CuboidRegion r = region(h, "api_own");
        UUID o = UUID.randomUUID();
        r.owners().add(o);
        h.assertTrue(dev.thefather007.worldguardneo.api.WorldGuardNeoAPI.isOwner(r, o), "API isOwner");
        h.assertTrue(!dev.thefather007.worldguardneo.api.WorldGuardNeoAPI.isMember(r, UUID.randomUUID()),
                "API isMember false for stranger");
        h.assertTrue(!dev.thefather007.worldguardneo.api.WorldGuardNeoAPI.hasBypass(combatantAt(h, new BlockPos(4, 1, 4))),
                "mock player has no bypass");
        h.assertTrue(dev.thefather007.worldguardneo.api.WorldGuardNeoAPI.isAvailable(), "API available");
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void apiRegisterCustomFlag(GameTestHelper h) {
        StateFlag custom = new StateFlag("gametest-marker", true);
        dev.thefather007.worldguardneo.api.WorldGuardNeoAPI.registerFlag(custom); // idempotent
        h.assertTrue(Flags.isRegistered("gametest-marker"), "custom flag registered");
        h.assertTrue(Flags.get("gametest-marker") != null, "custom flag resolvable by name");
        h.succeed();
    }

    // ---- flag (de)serialization / parsing ----

    @GameTest(template = TPL)
    public static void codecStateFlag(GameTestHelper h) {
        try {
            h.assertTrue(Flags.PVP.parse("allow") == StateFlag.State.ALLOW, "parse allow");
            h.assertTrue(Flags.PVP.parse("deny") == StateFlag.State.DENY, "parse deny");
        } catch (Exception e) {
            h.fail("valid state parse threw: " + e);
        }
        var json = Flags.PVP.toJson(StateFlag.State.DENY);
        h.assertTrue(Flags.PVP.fromJson(json) == StateFlag.State.DENY, "state flag json round-trip");
        boolean threw = false;
        try { Flags.PVP.parse("not-a-state"); } catch (Exception e) { threw = true; }
        h.assertTrue(threw, "invalid state value throws");
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void codecIntegerFlag(GameTestHelper h) {
        try {
            h.assertTrue(Flags.HEAL_DELAY.parse("42").equals(42), "parse integer");
        } catch (Exception e) {
            h.fail("valid integer parse threw: " + e);
        }
        boolean threw = false;
        try { Flags.HEAL_DELAY.parse("xyz"); } catch (Exception e) { threw = true; }
        h.assertTrue(threw, "invalid integer throws");
        var json = Flags.HEAL_DELAY.toJson(7);
        h.assertTrue(Flags.HEAL_DELAY.fromJson(json).equals(7), "integer json round-trip");
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void codecStringAndSetFlags(GameTestHelper h) {
        h.assertTrue("hello".equals(Flags.GREETING.parse("hello")), "string parse");
        var sj = Flags.GREETING.toJson("hi");
        h.assertTrue("hi".equals(Flags.GREETING.fromJson(sj)), "string json round-trip");
        Set<String> parsed = Flags.BLOCKED_CMDS.parse("tp,gamemode,give");
        h.assertTrue(parsed != null && parsed.contains("tp") && parsed.contains("give"), "set parse splits on comma");
        var setJson = Flags.BLOCKED_CMDS.toJson(Set.of("a", "b"));
        h.assertTrue(Flags.BLOCKED_CMDS.fromJson(setJson).equals(Set.of("a", "b")), "set json round-trip");
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void codecDoubleAndBooleanFlags(GameTestHelper h) {
        var dj = Flags.MAX_SPEED.toJson(0.25);
        h.assertTrue(Flags.MAX_SPEED.fromJson(dj).equals(0.25), "double json round-trip");
        var bj = Flags.NOTIFY_ENTER.toJson(true);
        h.assertTrue(Flags.NOTIFY_ENTER.fromJson(bj).equals(true), "boolean json round-trip");
        h.succeed();
    }

    // ---- EXIT group-from-source regression (resolveStateForRegion) ----

    @GameTest(template = TPL)
    public static void exitGroupInheritedFromParent(GameTestHelper h) {
        // Parent owns an OWNERS-scoped EXIT deny; the child inherits the flag. The resolver must
        // apply the PARENT's group, not the child's — the bug this guards against denied/allowed
        // the wrong players for inherited exit flags.
        CuboidRegion parent = makeRegion(h, "exitg_p", new BlockPos(0, 0, 0), new BlockPos(1, 1, 1));
        parent.setFlag(Flags.EXIT, StateFlag.State.DENY);
        parent.setFlagGroup(Flags.EXIT, RegionGroup.OWNERS);
        UUID owner = UUID.randomUUID(); parent.owners().add(owner);
        UUID stranger = UUID.randomUUID();
        CuboidRegion child = region(h, "exitg_c");
        child.setParent(parent);
        h.assertTrue(mgr(h).resolveStateForRegion(Flags.EXIT, child, owner) == StateFlag.State.DENY,
                "owner (parent's OWNERS group) gets the inherited EXIT deny");
        h.assertTrue(mgr(h).resolveStateForRegion(Flags.EXIT, child, stranger) == null,
                "stranger excluded by the parent's OWNERS group → no deny");
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void exitGroupDirectMembers(GameTestHelper h) {
        CuboidRegion r = region(h, "exitg_d");
        r.setFlag(Flags.EXIT_VEHICLE, StateFlag.State.DENY);
        r.setFlagGroup(Flags.EXIT_VEHICLE, RegionGroup.MEMBERS);
        UUID m = UUID.randomUUID(); r.members().add(m);
        UUID s = UUID.randomUUID();
        h.assertTrue(mgr(h).resolveStateForRegion(Flags.EXIT_VEHICLE, r, m) == StateFlag.State.DENY,
                "member gets the direct exit-vehicle deny");
        h.assertTrue(mgr(h).resolveStateForRegion(Flags.EXIT_VEHICLE, r, s) == null,
                "non-member excluded by the MEMBERS group");
        h.succeed();
    }

    // ---- robustness-fix regressions ----

    @GameTest(template = TPL)
    public static void doubleFlagRejectsNonFinite(GameTestHelper h) {
        try {
            h.assertTrue(Flags.MAX_SPEED.parse("0.5").equals(0.5), "a finite double still parses");
        } catch (Exception e) {
            h.fail("finite double parse threw: " + e);
        }
        for (String bad : new String[]{"NaN", "Infinity", "-Infinity"}) {
            boolean threw = false;
            try { Flags.MAX_SPEED.parse(bad); } catch (Exception e) { threw = true; }
            h.assertTrue(threw, "non-finite '" + bad + "' is rejected");
        }
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void uuidNameOfNullServerSafe(GameTestHelper h) {
        UUID u = UUID.randomUUID();
        // Must not NPE when the server is null (web-map popup during shutdown) — falls back to UUID.
        h.assertTrue(u.toString().equals(
                dev.thefather007.worldguardneo.util.UuidResolver.nameOf(null, u)),
                "nameOf(null server) falls back to the raw UUID");
        h.assertTrue("(none)".equals(
                dev.thefather007.worldguardneo.util.UuidResolver.nameOf(null, null)),
                "nameOf(null, null) → (none)");
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void regionRenamePreservesEverything(GameTestHelper h) {
        // The RegionManager is shared across the whole gametest level; rn_new is only ever produced
        // by rename() (never by region()/makeRegion(), which self-clean), so drop it up front to keep
        // this test idempotent if the level is reused.
        mgr(h).remove("rn_new");
        CuboidRegion r = region(h, "rn_old");
        r.setPriority(7);
        r.setFlag(Flags.PVP, StateFlag.State.DENY);
        UUID owner = UUID.randomUUID();  r.owners().add(owner);
        UUID member = UUID.randomUUID(); r.members().add(member);
        CuboidRegion child = makeRegion(h, "rn_child", new BlockPos(0, 0, 0), new BlockPos(1, 1, 1));
        child.setParent(r);

        var renamed = mgr(h).rename("rn_old", "rn_new");
        h.assertTrue(renamed.isPresent(), "rename returns the new region");
        h.assertTrue(mgr(h).get("rn_old").isEmpty(), "old id is freed");

        var nw = mgr(h).get("rn_new");
        h.assertTrue(nw.isPresent(), "new id exists");
        h.assertTrue(nw.get().priority() == 7, "priority preserved");
        h.assertTrue(nw.get().getFlag(Flags.PVP) == StateFlag.State.DENY, "flags preserved");
        h.assertTrue(nw.get().isOwner(owner), "owner preserved");
        h.assertTrue(nw.get().isMember(member), "member preserved");
        h.assertTrue(child.parent() == nw.get(), "child re-pointed to renamed region");
        h.assertTrue(mgr(h).rename("rn_new", "rn_child").isEmpty(), "rename to a taken id fails");
        h.succeed();
    }

    /* ---------------- region lifecycle metadata ---------------- */

    @GameTest(template = TPL)
    public static void regionMetadataLifecycle(GameTestHelper h) {
        CuboidRegion r = region(h, "meta_life");
        h.assertTrue(r.createdAt() > 0, "createdAt is stamped on construction");
        h.assertTrue(r.modifiedAt() >= r.createdAt(), "modifiedAt starts at/after createdAt");
        h.assertTrue(r.createdBy() == null, "createdBy is null until set");

        UUID who = UUID.randomUUID();
        r.setCreatedBy(who);
        h.assertTrue(who.equals(r.createdBy()), "createdBy round-trips");

        r.setModifiedAt(1_000L);                       // pretend the last edit was long ago
        r.setFlag(Flags.PVP, StateFlag.State.DENY);    // a mutation must refresh modifiedAt
        h.assertTrue(r.modifiedAt() > 1_000L, "setFlag refreshes modifiedAt");
        h.succeed();
    }

    @GameTest(template = TPL)
    public static void metadataSurvivesCodecRoundTrip(GameTestHelper h) {
        CuboidRegion r = region(h, "meta_codec");
        UUID who = UUID.randomUUID();
        r.setCreatedBy(who);
        r.setCreatedAt(1_700_000_000_000L);
        r.setModifiedAt(1_700_000_500_000L);

        var json = dev.thefather007.worldguardneo.storage.RegionJsonCodec.regionToJson(r);
        ProtectedRegion back = dev.thefather007.worldguardneo.storage.RegionJsonCodec.readRegion(
                "meta_codec", json, new java.util.HashMap<>());
        h.assertTrue(back != null, "region parses back");
        h.assertTrue(back.createdAt()  == 1_700_000_000_000L, "createdAt persisted");
        h.assertTrue(back.modifiedAt() == 1_700_000_500_000L, "modifiedAt persisted");
        h.assertTrue(who.equals(back.createdBy()), "createdBy persisted");
        h.succeed();
    }

    /* ---------------- new entity-interaction flags ---------------- */

    @GameTest(template = TPL)
    public static void newEntityFlagsRegisteredAndDefaultAllow(GameTestHelper h) {
        h.assertTrue(Flags.get("villager-trade") != null, "villager-trade is registered");
        h.assertTrue(Flags.get("ride") != null,           "ride is registered");
        h.assertTrue(Flags.get("entity-leash") != null,   "entity-leash is registered");

        BlockPos abs = h.absolutePos(new BlockPos(4, 2, 4));
        UUID stranger = UUID.randomUUID();
        RegionManager m = mgr(h);
        region(h, "ent_flags"); // covers the platform, no flags set
        // Unset → default ALLOW for a stranger.
        h.assertTrue(m.testState(Flags.VILLAGER_TRADE, stranger, abs.getX(), abs.getY(), abs.getZ()),
                "villager-trade defaults to allow");
        h.assertTrue(m.testState(Flags.RIDE, stranger, abs.getX(), abs.getY(), abs.getZ()),
                "ride defaults to allow");
        h.assertTrue(m.testState(Flags.ENTITY_LEASH, stranger, abs.getX(), abs.getY(), abs.getZ()),
                "entity-leash defaults to allow");

        // Explicit DENY is honoured.
        region(h, "ent_flags").setFlag(Flags.RIDE, StateFlag.State.DENY);
        h.assertTrue(!m.testState(Flags.RIDE, stranger, abs.getX(), abs.getY(), abs.getZ()),
                "ride deny is enforced");
        h.succeed();
    }

    /* ---------------- spawn/teleport Y-clamp ---------------- */

    @GameTest(template = TPL)
    public static void spawnTeleportYClampedToBuildHeight(GameTestHelper h) {
        ServerLevel lvl = h.getLevel();
        double min = lvl.getMinBuildHeight();
        double max = lvl.getMaxBuildHeight() - 1;

        h.assertTrue(dev.thefather007.worldguardneo.util.Locations.clampY(lvl, 1_000_000) == max,
                "Y above the world clamps to maxBuildHeight-1");
        h.assertTrue(dev.thefather007.worldguardneo.util.Locations.clampY(lvl, -1_000_000) == min,
                "Y below the world clamps to minBuildHeight");
        double inRange = min + 5;
        h.assertTrue(dev.thefather007.worldguardneo.util.Locations.clampY(lvl, inRange) == inRange,
                "in-range Y is left untouched");
        h.succeed();
    }
}
