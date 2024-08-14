package yan.lx.bedrockminer.utils;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.BlockView;


public class BlockBreakerUtils2 {
    public static float calcBlockBreakingDeltaMax = 0.7F;
    private static boolean breakingBlock = false;
    private static BlockPos currentBreakingPos = new BlockPos(-1, -1, -1);
    private static float blockBreakingSoundCooldown = 0F;
    private static float currentBreakingProgress = 0F;


    public static boolean attackBlock(BlockPos pos) {
        return attackBlock(pos, Direction.UP);
    }

    public static boolean attackBlock(BlockPos pos, Direction direction) {
        var mc = MinecraftClient.getInstance();
        var world = mc.world;
        var player = mc.player;
        var networkHandler = mc.getNetworkHandler();
        var interactionManager = mc.interactionManager;
        if (world == null || player == null || networkHandler == null || interactionManager == null) return false;

        if (player.isBlockBreakingRestricted(world, pos, interactionManager.getCurrentGameMode())) {
            return false;
        } else if (!world.getWorldBorder().contains(pos)) {
            return false;
        } else {
            BlockState blockState;
            if (interactionManager.getCurrentGameMode().isCreative()) {
                blockState = world.getBlockState(pos);
                mc.getTutorialManager().onBlockBreaking(world, pos, blockState, 1.0F);
                interactionManager.sendSequencedPacket(world, (sequence) -> {
                    interactionManager.breakBlock(pos);
                    return new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, pos, direction, sequence);
                });
            } else if (!breakingBlock || !isCurrentlyBreaking(pos)) {
                if (breakingBlock) {
                    networkHandler.sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK, currentBreakingPos, direction));
                }

                blockState = world.getBlockState(pos);
                mc.getTutorialManager().onBlockBreaking(world, pos, blockState, 0.0F);
                interactionManager.sendSequencedPacket(world, (sequence) -> {
                    boolean bl = !blockState.isAir();
                    if (bl && currentBreakingProgress == 0.0F) {
                        blockState.onBlockBreakStart(world, pos, player);
                    }

                    if (bl && blockState.calcBlockBreakingDelta(player, player.getWorld(), pos) >= calcBlockBreakingDeltaMax) {
                        interactionManager.breakBlock(pos);
                    } else {
                        breakingBlock = true;
                        currentBreakingPos = pos;
                        currentBreakingProgress = 0.0F;
                        blockBreakingSoundCooldown = 0.0F;
                        world.setBlockBreakingInfo(player.getId(), currentBreakingPos, getBlockBreakingProgress());
                    }

                    return new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, pos, direction, sequence);
                });
            }

            return true;
        }
    }

    public static void cancelBlockBreaking() {
        var mc = MinecraftClient.getInstance();
        var world = mc.world;
        var player = mc.player;
        var networkHandler = mc.getNetworkHandler();
        if (world == null || player == null || networkHandler == null) return;
        if (breakingBlock) {
            BlockState blockState = world.getBlockState(currentBreakingPos);
            mc.getTutorialManager().onBlockBreaking(world, currentBreakingPos, blockState, -1.0F);
            networkHandler.sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK, currentBreakingPos, Direction.DOWN));
            breakingBlock = false;
            currentBreakingProgress = 0.0F;
            world.setBlockBreakingInfo(player.getId(), currentBreakingPos, -1);
            player.resetLastAttackedTicks();
        }

    }

    public static boolean updateBlockBreakingProgress(BlockPos pos) {
        return updateBlockBreakingProgress(pos, Direction.UP);
    }

    public static boolean updateBlockBreakingProgress(BlockPos pos, Direction direction) {
        var mc = MinecraftClient.getInstance();
        var world = mc.world;
        var player = mc.player;
        var networkHandler = mc.getNetworkHandler();
        var interactionManager = mc.interactionManager;
        if (world == null || player == null || networkHandler == null || interactionManager == null) return false;
        interactionManager.syncSelectedSlot();
        BlockState blockState;
        if (interactionManager.getCurrentGameMode().isCreative() && world.getWorldBorder().contains(pos)) {
            blockState = world.getBlockState(pos);
            mc.getTutorialManager().onBlockBreaking(world, pos, blockState, 0.7F);
            interactionManager.sendSequencedPacket(world, (sequence) -> {
                interactionManager.breakBlock(pos);
                return new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, pos, direction, sequence);
            });
            return true;
        } else if (isCurrentlyBreaking(pos)) {
            blockState = world.getBlockState(pos);
            if (blockState.isAir()) {
                breakingBlock = false;
                return false;
            } else {
                currentBreakingProgress += blockState.calcBlockBreakingDelta(player, player.getWorld(), pos);
                if (blockBreakingSoundCooldown % 4.0F == 0.0F) {
                    BlockSoundGroup blockSoundGroup = blockState.getSoundGroup();
                    mc.getSoundManager().play(new PositionedSoundInstance(blockSoundGroup.getHitSound(), SoundCategory.BLOCKS, (blockSoundGroup.getVolume() + 1.0F) / 8.0F, blockSoundGroup.getPitch() * 0.5F, SoundInstance.createRandom(), pos));
                }
                ++blockBreakingSoundCooldown;
                mc.getTutorialManager().onBlockBreaking(world, pos, blockState, MathHelper.clamp(currentBreakingProgress, 0.0F, 1.0F));
                if (currentBreakingProgress >= calcBlockBreakingDeltaMax) {
                    breakingBlock = false;
                    interactionManager.sendSequencedPacket(world, (sequence) -> {
                        interactionManager.breakBlock(pos);
                        return new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, pos, direction, sequence);
                    });
                    currentBreakingProgress = 0.0F;
                    blockBreakingSoundCooldown = 0.0F;
                }
                world.setBlockBreakingInfo(player.getId(), currentBreakingPos, getBlockBreakingProgress());
                return true;
            }
        } else {
            return attackBlock(pos, direction);
        }

    }

    /**
     * 计算玩家破坏方块的速度
     *
     * @param state  方块状态
     * @param player 玩家实体
     * @param world  游戏世界视图
     * @param pos    方块位置
     * @return 玩家破坏方块的速度
     */
    private static float calcBlockBreakingDelta(BlockState state, PlayerEntity player, BlockView world, BlockPos pos) {
        // 获取方块在指定位置的硬度
        var hardness = state.getHardness(world, pos);
        // 如果方块硬度为 -1.0F，表示方块不可破坏，直接返回 0.0
        if (hardness == -1.0F) {
            return 0.0F;
        } else {
            // 获取玩家的物品栏
            var inventory = player.getInventory();
            // 根据玩家是否能够收获该方块，设置不同的倍率
            var speedMultiplier = canHarvest(state, player) ? 30 : 100;
            // 计算并返回玩家破坏方块的速度
            return InventoryManagerUtils.getBlockBreakingSpeed(state, inventory.getMainHandStack()) / hardness / (float) speedMultiplier;
        }
    }

    /**
     * 判断玩家是否能够收获（破坏）给定的方块状态
     *
     * @param state  方块状态
     * @param player 玩家实体
     * @return 如果玩家能够收获该方块，返回 true，否则返回 false
     */
    private static boolean canHarvest(BlockState state, PlayerEntity player) {
        // 方块不需要特定工具来破坏 或 玩家主手中的工具适合破坏该方块
        return !state.isToolRequired() || player.getInventory().getMainHandStack().isSuitableFor(state);
    }

    private static int getBlockBreakingProgress() {
        return currentBreakingProgress > 0.0F ? (int) (currentBreakingProgress * 10.0F) : -1;
    }

    private static boolean isCurrentlyBreaking(BlockPos pos) {
        return pos.equals(currentBreakingPos);
    }
}