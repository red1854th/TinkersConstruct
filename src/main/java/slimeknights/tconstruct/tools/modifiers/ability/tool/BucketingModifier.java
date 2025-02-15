package slimeknights.tconstruct.tools.modifiers.ability.tool;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.IBucketPickupHandler;
import net.minecraft.block.ILiquidContainer;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.fluid.FlowingFluid;
import net.minecraft.fluid.Fluid;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.inventory.EquipmentSlotType;
import net.minecraft.inventory.EquipmentSlotType.Group;
import net.minecraft.item.ItemUseContext;
import net.minecraft.particles.ParticleTypes;
import net.minecraft.tags.FluidTags;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ActionResultType;
import net.minecraft.util.Direction;
import net.minecraft.util.Hand;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.BlockRayTraceResult;
import net.minecraft.util.math.RayTraceContext;
import net.minecraft.util.math.RayTraceResult.Type;
import net.minecraft.world.World;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fluids.FluidAttributes;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandler.FluidAction;
import slimeknights.tconstruct.TConstruct;
import slimeknights.tconstruct.library.modifiers.TankModifier;
import slimeknights.tconstruct.library.tools.ToolDefinition;
import slimeknights.tconstruct.library.tools.capability.TinkerDataKeys;
import slimeknights.tconstruct.library.tools.context.EquipmentChangeContext;
import slimeknights.tconstruct.library.tools.helper.ModifierUtil;
import slimeknights.tconstruct.library.tools.item.ModifiableItem;
import slimeknights.tconstruct.library.tools.nbt.IModDataReadOnly;
import slimeknights.tconstruct.library.tools.nbt.IModifierToolStack;
import slimeknights.tconstruct.library.tools.nbt.ModDataNBT;
import slimeknights.tconstruct.library.tools.nbt.StatsNBT;

public class BucketingModifier extends TankModifier {
  public BucketingModifier() {
    super(0xD8D8D8, FluidAttributes.BUCKET_VOLUME);
  }

  @Override
  public int getPriority() {
    return 80; // little bit less so we get to add volatile data late
  }

  @Override
  public void onEquip(IModifierToolStack tool, int level, EquipmentChangeContext context) {
    if (context.getChangedSlot() == EquipmentSlotType.CHEST) {
      ModifierUtil.addTotalArmorModifierLevel(tool, context, TinkerDataKeys.SHOW_EMPTY_OFFHAND, 1, true);
    }
  }

  @Override
  public void onUnequip(IModifierToolStack tool, int level, EquipmentChangeContext context) {
    if (context.getChangedSlot() == EquipmentSlotType.CHEST) {
      ModifierUtil.addTotalArmorModifierLevel(tool, context, TinkerDataKeys.SHOW_EMPTY_OFFHAND, -1, true);
    }
  }

  @Override
  public void addVolatileData(ToolDefinition toolDefinition, StatsNBT baseStats, IModDataReadOnly persistentData, int level, ModDataNBT volatileData) {
    super.addVolatileData(toolDefinition, baseStats, persistentData, level, volatileData);

    // boost to the nearest bucket amount
    int capacity = getCapacity(volatileData);
    int remainder = capacity % FluidAttributes.BUCKET_VOLUME;
    if (remainder != 0) {
      addCapacity(volatileData, FluidAttributes.BUCKET_VOLUME - remainder);
    }
  }

  /**
   * Checks if the block is unable to contain fluid
   * @param world  World
   * @param pos    Position to try
   * @param state  State
   * @param fluid  Fluid to place
   * @return  True if the block is unable to contain fluid, false if it can contain fluid
   */
  private static boolean cannotContainFluid(World world, BlockPos pos, BlockState state, Fluid fluid) {
    Block block = state.getBlock();
    return !state.isReplaceable(fluid) && (!(block instanceof ILiquidContainer) || !((ILiquidContainer)block).canContainFluid(world, pos, state, fluid));
  }

  @Override
  public ActionResultType beforeBlockUse(IModifierToolStack tool, int level, ItemUseContext context, EquipmentSlotType slot) {
    if (slot.getSlotType() != Group.ARMOR) {
      return ActionResultType.PASS;
    }

    World world = context.getWorld();
    BlockPos target = context.getPos();
    // must have a TE that has a fluid handler capability
    TileEntity te = world.getTileEntity(target);
    if (te == null) {
      return ActionResultType.PASS;
    }
    Direction face = context.getFace();
    LazyOptional<IFluidHandler> capability = te.getCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, face);
    if (!capability.isPresent()) {
      return ActionResultType.PASS;
    }

    // only the server needs to deal with actually handling stuff
    if (!world.isRemote) {
      PlayerEntity player = context.getPlayer();
      boolean sneaking = player != null && player.isSneaking();
      capability.ifPresent(cap -> {
        FluidStack fluidStack = getFluid(tool);
        // sneaking fills, not sneak drains
        SoundEvent sound = null;
        if (sneaking) {
          // must have something to fill
          if (!fluidStack.isEmpty()) {
            int added = cap.fill(fluidStack, FluidAction.EXECUTE);
            if (added > 0) {
              sound = fluidStack.getFluid().getAttributes().getEmptySound(fluidStack);
              fluidStack.shrink(added);
              setFluid(tool, fluidStack);
            }
          }
          // if nothing currently, will drain whatever
        } else if (fluidStack.isEmpty()) {
          FluidStack drained = cap.drain(getCapacity(tool), FluidAction.EXECUTE);
          if (!drained.isEmpty()) {
            setFluid(tool, drained);
            sound = drained.getFluid().getAttributes().getFillSound(drained);
          }
        } else {
          // filter drained to be the same as the current fluid
          FluidStack drained = cap.drain(new FluidStack(fluidStack, getCapacity(tool) - fluidStack.getAmount()), FluidAction.EXECUTE);
          if (!drained.isEmpty() && drained.isFluidEqual(fluidStack)) {
            fluidStack.grow(drained.getAmount());
            setFluid(tool, fluidStack);
            sound = drained.getFluid().getAttributes().getFillSound(drained);
          }
        }
        if (sound != null) {
          world.playSound(null, target, sound, SoundCategory.BLOCKS, 1.0F, 1.0F);
        }
      });
    }
    return ActionResultType.func_233537_a_(world.isRemote);
  }

  @Override
  public ActionResultType afterBlockUse(IModifierToolStack tool, int level, ItemUseContext context, EquipmentSlotType slotType) {
    // only place fluid if sneaking, we contain at least a bucket, and its a block
    PlayerEntity player = context.getPlayer();
    if (player == null || !player.isSneaking()) {
      return ActionResultType.PASS;
    }
    FluidStack fluidStack = getFluid(tool);
    if (fluidStack.getAmount() < FluidAttributes.BUCKET_VOLUME) {
      return ActionResultType.PASS;
    }
    Fluid fluid = fluidStack.getFluid();
    if (!(fluid instanceof FlowingFluid)) {
      return ActionResultType.PASS;
    }

    // can we interact with the position
    Direction face = context.getFace();
    World world = context.getWorld();
    BlockPos target = context.getPos();
    BlockPos offset = target.offset(face);
    if (!world.isBlockModifiable(player, target) || !player.canPlayerEdit(offset, face, context.getItem())) {
      return ActionResultType.PASS;
    }

    // if the block cannot be placed at the current location, try placing at the neighbor
    BlockState existing = world.getBlockState(target);
    if (cannotContainFluid(world, target, existing, fluidStack.getFluid())) {
      target = offset;
      existing = world.getBlockState(target);
      if (cannotContainFluid(world, target, existing, fluidStack.getFluid())) {
        return ActionResultType.PASS;
      }
    }

    // if water, evaporate
    boolean placed = false;
    if (world.getDimensionType().isUltrawarm() && fluid.isIn(FluidTags.WATER)) {
      world.playSound(player, target, SoundEvents.BLOCK_FIRE_EXTINGUISH, SoundCategory.BLOCKS, 0.5F, 2.6F + (world.rand.nextFloat() - world.rand.nextFloat()) * 0.8F);
      for(int l = 0; l < 8; ++l) {
        world.addParticle(ParticleTypes.LARGE_SMOKE, target.getX() + Math.random(), target.getY() + Math.random(), target.getZ() + Math.random(), 0.0D, 0.0D, 0.0D);
      }
      placed = true;
    } else if (existing.isReplaceable(fluid)) {
      // if its a liquid container, we should have validated it already
      if (!world.isRemote && !existing.getMaterial().isLiquid()) {
        world.destroyBlock(target, true);
      }
      if (world.setBlockState(target, fluid.getDefaultState().getBlockState()) || existing.getFluidState().isSource()) {
        world.playSound(null, target, fluid.getAttributes().getEmptySound(fluidStack), SoundCategory.BLOCKS, 1.0F, 1.0F);
        placed = true;
      }
    } else if (existing.getBlock() instanceof ILiquidContainer) {
      // if not replaceable, it must be a liquid container
      ((ILiquidContainer) existing.getBlock()).receiveFluid(world, target, existing, ((FlowingFluid)fluid).getStillFluidState(false));
      world.playSound(null, target, fluid.getAttributes().getEmptySound(fluidStack), SoundCategory.BLOCKS, 1.0F, 1.0F);
      placed = true;
    }

    // if we placed something, consume fluid
    if (placed) {
      drain(tool, fluidStack, FluidAttributes.BUCKET_VOLUME);
      return ActionResultType.SUCCESS;
    }
    return ActionResultType.PASS;
  }

  @Override
  public ActionResultType onToolUse(IModifierToolStack tool, int level, World world, PlayerEntity player, Hand hand, EquipmentSlotType slotType) {
    if (player.isCrouching()) {
      return ActionResultType.PASS;
    }

    // need at least a bucket worth of empty space
    FluidStack fluidStack = getFluid(tool);
    if (getCapacity(tool) - fluidStack.getAmount() < FluidAttributes.BUCKET_VOLUME) {
      return ActionResultType.PASS;
    }
    // have to trace again to find the fluid, ensure we can edit the position
    BlockRayTraceResult trace = ModifiableItem.blockRayTrace(world, player, RayTraceContext.FluidMode.SOURCE_ONLY);
    if (trace.getType() != Type.BLOCK) {
      return ActionResultType.PASS;
    }
    Direction face = trace.getFace();
    BlockPos target = trace.getPos();
    BlockPos offset = target.offset(face);
    if (!world.isBlockModifiable(player, target) || !player.canPlayerEdit(offset, face, player.getItemStackFromSlot(slotType))) {
      return ActionResultType.PASS;
    }
    // try to find a fluid here
    FluidState fluidState = world.getFluidState(target);
    Fluid currentFluid = fluidStack.getFluid();
    if (fluidState.isEmpty() || (!fluidStack.isEmpty() && !currentFluid.isEquivalentTo(fluidState.getFluid()))) {
      return ActionResultType.PASS;
    }
    // finally, pickup the fluid
    BlockState state = world.getBlockState(target);
    if (state.getBlock() instanceof IBucketPickupHandler) {
      Fluid pickedUpFluid = ((IBucketPickupHandler)state.getBlock()).pickupFluid(world, target, state);
      if (pickedUpFluid != Fluids.EMPTY) {
        player.playSound(pickedUpFluid.getAttributes().getFillSound(fluidStack), 1.0F, 1.0F);
        // set the fluid if empty, increase the fluid if filled
        if (!world.isRemote) {
          if (fluidStack.isEmpty()) {
            setFluid(tool, new FluidStack(pickedUpFluid, FluidAttributes.BUCKET_VOLUME));
          } else if (pickedUpFluid == currentFluid) {
            fluidStack.grow(FluidAttributes.BUCKET_VOLUME);
            setFluid(tool, fluidStack);
          } else {
            TConstruct.LOG.error("Picked up a fluid {} that does not match the current fluid state {}, this should not happen", pickedUpFluid, fluidState.getFluid());
          }
        }
        return ActionResultType.SUCCESS;
      }
    }
    return ActionResultType.PASS;
  }
}
