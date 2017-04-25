package ebf.tim.registry;

import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.registry.GameRegistry;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import ebf.tim.TrainsInMotion;
import ebf.tim.blocks.BlockTrainFluid;
import ebf.tim.blocks.LampBlock;
import ebf.tim.tileentities.TileEntityStorage;
import ebf.tim.blocks.BlockDynamic;
import net.minecraft.block.Block;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.material.MapColor;
import net.minecraft.block.material.Material;
import net.minecraft.block.material.MaterialLiquid;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemBucket;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidContainerRegistry;
import net.minecraftforge.fluids.FluidRegistry;

import static cpw.mods.fml.common.registry.GameRegistry.addRecipe;

/**
 * <h1>Generic registry</h1>
 * this class lists all the blocks, fluids, and non-train/rollingstock items provided by this mod.
 * If you need a reference to one of those you have to call it from this class.
 * @author Eternal Blue Flame
 * @author Justice
 */

public class GenericRegistry {
    //initialize the oil
    public static BlockTrainFluid blockFluidOil;
    public static BlockTrainFluid blockFluidDiesel;
    public static final Fluid fluidOil = new Fluid("Oil").setUnlocalizedName("fluid.oil").setBlock(blockFluidOil).setGaseous(false).setDensity(700);
    public static final Fluid fluidDiesel = new Fluid("Diesel").setUnlocalizedName("fluid.diesel").setBlock(blockFluidDiesel).setGaseous(false).setDensity(500);
    public static ItemBucket bucketOil;
    public static ItemBucket bucketDiesel;

    //define the train crafting table.
    public static BlockDynamic trainTable = new BlockDynamic("blocktraintable",Material.wood, TrainsInMotion.blockTypes.CRAFTING);

    //initialize the lamp block
    public static Block lampBlock;


    /**
     * <h2>Block and Item register function</h2>
     * called by the main class to register the blocks and items
     * @see TrainsInMotion#init(FMLInitializationEvent)
     */
    public static void RegisterStuff(){
        /**
         * <h3>register Blocks</h3>
         */
        //register oil
        //register fluid and it's block
        FluidRegistry.registerFluid(fluidOil);
        blockFluidOil = new BlockTrainFluid(fluidOil, new MaterialLiquid(MapColor.blackColor));
        blockFluidOil.setBlockName("block.oil");
        GameRegistry.registerBlock(blockFluidOil, "block.oil");
        //register the bucket item
        bucketOil = new ItemBucket(GenericRegistry.blockFluidOil);
        bucketOil.setCreativeTab(TrainsInMotion.creativeTab).setUnlocalizedName("item.oilbucket").setContainerItem(Items.bucket);
        GameRegistry.registerItem(bucketOil, "fluid.oil.bucket");
        FluidContainerRegistry.registerFluidContainer(fluidOil, new ItemStack(bucketOil), new ItemStack(Items.bucket));
        //register diesel
        //register fluid and it's block
        FluidRegistry.registerFluid(fluidDiesel);
        blockFluidDiesel = new BlockTrainFluid(fluidDiesel, new MaterialLiquid(MapColor.dirtColor)).setFlammable(true, 1);
        blockFluidDiesel.setBlockName("block.diesel");
        GameRegistry.registerBlock(blockFluidDiesel, "block.diesel");
        //register the bucket item
        bucketDiesel = new ItemBucket(GenericRegistry.blockFluidDiesel);
        bucketDiesel.setCreativeTab(TrainsInMotion.creativeTab).setUnlocalizedName("item.dieselbucket").setContainerItem(Items.bucket);
        GameRegistry.registerItem(bucketDiesel, "fluid.diesel.bucket");
        FluidContainerRegistry.registerFluidContainer(fluidDiesel, new ItemStack(bucketDiesel), new ItemStack(Items.bucket));

        GameRegistry.registerBlock(trainTable, "TrainTable");
        GameRegistry.registerTileEntity(TileEntityStorage.class, "StorageEntity");
        addRecipe(new ItemStack(trainTable, 1),  "WWW", "WIW", "WWW", 'W', Blocks.planks, 'I', Items.iron_ingot);

        /**
         * <h3>register Items</h3>
         */
    }

    /**
     * <h2>Client onlyBlock and Item register function</h2>
     * called by the main class to register the blocks and items, these are only on the client side.
     * @see TrainsInMotion#init(FMLInitializationEvent)
     */
    @SideOnly(Side.CLIENT)
    public static void RegisterClientStuff(){
        lampBlock = new LampBlock();
        GameRegistry.registerBlock(lampBlock, "lampblock");
        lampBlock.setLightLevel(1f);

        //register the fluid icons
        fluidOil.setIcons(BlockLiquid.getLiquidIcon("water_still"), BlockLiquid.getLiquidIcon("water_flow"));
    }

}