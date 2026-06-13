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
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/**
 * In-game GameTest battery for WorldGuardNeo, exercising the REAL event/mixin enforcement path
 * inside a live {@link ServerLevel} — the layer the standalone JVM unit tests in {@code tests/}
 * can't reach. Each test creates a region over the test platform, performs a real world action
 * (a mock player breaking/using a block, a forced random tick, spawning an entity, …) and asserts
 * the protection outcome.
 *
 * <h2>Running</h2>
 * These run via the dedicated GameTest server (the {@code gameTestServer} Gradle run), or in-game
 * with {@code /test runall} on a server that has WorldGuardNeo's gametest namespace enabled.
 * WorldEdit is a required dependency of the mod, so the test environment must include it (region
 * creation here is programmatic and does NOT use WorldEdit, but the mod won't load without it).
 *
 * <p>All tests share the {@code worldguardneo:platform} structure (a 9×6×9 stone floor).
 */
@GameTestHolder(WorldGuardNeo.MOD_ID)
@PrefixGameTestTemplate(false)
public final class WGNGameTests {

    private static final String TPL = "worldguardneo:platform";
    private static final UUID MEMBER_UUID = UUID.fromString("00000000-0000-0000-0000-00000000abcd");

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
        h.useBlock(h.absolutePos(rel), p);
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
        h.useBlock(h.absolutePos(rel), p);
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
        h.useBlock(h.absolutePos(rel), p);
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
        ServerPlayer attacker = stranger(h);
        ServerPlayer victim = stranger(h);
        float before = victim.getHealth();
        attacker.attack(victim); // routes through hurt() → LivingIncomingDamageEvent → pvp gate
        h.assertTrue(victim.getHealth() == before, "pvp deny → victim took no damage");
        h.succeed();
    }

    /* ---------------- BLOCK-PLACE ---------------- */

    @GameTest(template = TPL)
    public static void placeDeniedForStranger(GameTestHelper h) {
        region(h, "gt_place_deny");
        BlockPos floorRel = new BlockPos(4, 0, 4); // place ON TOP of this → (4,1,4)
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
        BlockPos floorRel = new BlockPos(4, 0, 4);
        placeBlockOnTop(h, p, floorRel, Items.STONE);
        h.assertBlockPresent(Blocks.STONE, floorRel.above());
        h.succeed();
    }

    private static void placeBlockOnTop(GameTestHelper h, ServerPlayer p, BlockPos floorRel,
                                        net.minecraft.world.item.Item item) {
        ItemStack stack = new ItemStack(item, 64);
        p.setItemInHand(InteractionHand.MAIN_HAND, stack);
        BlockPos floorAbs = h.absolutePos(floorRel);
        Vec3 hitVec = new Vec3(floorAbs.getX() + 0.5, floorAbs.getY() + 1.0, floorAbs.getZ() + 0.5);
        BlockHitResult hit = new BlockHitResult(hitVec, Direction.UP, floorAbs, false);
        p.gameMode.useItemOn(p, h.getLevel(), stack, InteractionHand.MAIN_HAND, hit);
    }

    /* ---------------- CHEST-ACCESS ---------------- */

    @GameTest(template = TPL)
    public static void chestAccessDeniedForStranger(GameTestHelper h) {
        region(h, "gt_chest_deny");
        BlockPos rel = new BlockPos(4, 1, 4);
        h.setBlock(rel, Blocks.CHEST);
        ServerPlayer p = stranger(h);
        h.useBlock(h.absolutePos(rel), p);
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
        h.useBlock(h.absolutePos(rel), p);
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
}
