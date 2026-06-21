package dev.thefather007.worldguardneo.gametest;

import dev.thefather007.worldguardneo.WorldGuardNeo;
import dev.thefather007.worldguardneo.flags.Flags;
import dev.thefather007.worldguardneo.flags.StateFlag;
import dev.thefather007.worldguardneo.region.CuboidRegion;
import dev.thefather007.worldguardneo.region.ProtectedRegion;
import dev.thefather007.worldguardneo.region.RegionManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.decoration.ItemFrame;
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

import java.util.List;
import java.util.UUID;

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
        float before = victim.getHealth();
        // Direct hurt() with a fixed amount → LivingIncomingDamageEvent → pvp gate (avoids
        // attack-strength scaling). The victim sits inside the region so PVP=DENY applies.
        victim.hurt(attacker.damageSources().playerAttack(attacker), 4.0f);
        h.assertTrue(victim.getHealth() == before, "pvp deny → victim took no damage");
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
        float before = victim.getHealth();
        // Victim sits inside the region (PVP=ALLOW) with creative invulnerability cleared, so the
        // mod must NOT cancel and the damage lands.
        victim.hurt(attacker.damageSources().playerAttack(attacker), 4.0f);
        h.assertTrue(victim.getHealth() < before, "pvp allow → victim took damage");
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
}
