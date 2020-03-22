package ebf.tim.entities;

import cofh.api.energy.IEnergyContainerItem;
import com.mojang.authlib.GameProfile;
import cpw.mods.fml.common.registry.IEntityAdditionalSpawnData;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import ebf.tim.TrainsInMotion;
import ebf.tim.api.SkinRegistry;
import ebf.tim.api.skin;
import ebf.tim.items.ItemKey;
import ebf.tim.items.ItemPaintBucket;
import ebf.tim.items.ItemTicket;
import ebf.tim.models.Bogie;
import ebf.tim.models.ParticleFX;
import ebf.tim.models.TransportRenderData;
import ebf.tim.networking.PacketInteract;
import ebf.tim.networking.PacketRemove;
import ebf.tim.registry.NBTKeys;
import ebf.tim.utility.*;
import fexcraft.tmt.slim.ModelBase;
import io.netty.buffer.ByteBuf;
import mods.railcraft.api.carts.IFluidCart;
import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.item.EntityMinecart;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemBucket;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntityFurnace;
import net.minecraft.util.*;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.World;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.*;

import javax.annotation.Nullable;
import java.util.*;

import static ebf.tim.TrainsInMotion.transportTypes.*;
import static ebf.tim.utility.RailUtility.rotatePointF;

/**
 * <h1>Generic Rail Transport</h1>
 * this is the base for all trains and rollingstock.
 * @author Eternal Blue Flame
 */
public class GenericRailTransport extends EntityMinecart implements IEntityAdditionalSpawnData, IInventory, IFluidHandler, IFluidCart{

    /*
     * <h2>variables</h2>
     */
    /**defines the colors, the outer array is for each different color, and the inner int[] is for the RGB color*/
    public List<Integer> colorsFrom = new ArrayList<>();
    public List<Integer> colorsTo = new ArrayList<>();
    /**the server-sided persistent UUID of the owner*/
    private UUID owner = null;
    /**the front entity bogie*/
    public EntityBogie frontBogie = null;
    /**the back entity bogie*/
    public EntityBogie backBogie = null;
    /**the list of seat entities*/
    public List<EntitySeat> seats = new ArrayList<>();
    /**the server-sided persistent UUID of the transport linked to the front of this,*/
    public UUID frontLinkedTransport = null;
    /**the id of the rollingstock linked to the front*/
    public Integer frontLinkedID =null;
    /**the server-sided persistent UUID of the transport linked to the back of this,*/
    public UUID backLinkedTransport = null;
    /**the id of the rollingstock linked to the back*/
    public Integer backLinkedID = null;
    /**the ID of the owner*/
    public String ownerName ="";
    /**the destination for routing*/
    public String destination ="";
    /**used to initialize a large number of variables that are used to calculate everything from movement to linking.
     * this is so we don't have to initialize each of these variables every tick, saves CPU.*/
    public float[][] vectorCache = new float[7][3];
    /**the health of the entity, similar to that of EntityLiving*/
    private int health = 20;
    /**the fluidTank tank*/
    private FluidTankInfo[] fluidTank = null;
    /**local cache for fluid tanks, to check if parsing is necessary or not*/
    private String fluidCache="";
    /**the list of items used for the inventory and crafting slots.*/
    public List<ItemStackSlot> inventory = null;
    /**whether or not this needs to update the datawatchers*/
    public boolean updateWatchers = false;
    /**the ticket that gives the entity permission to load chunks.*/
    private ForgeChunkManager.Ticket chunkTicket;
    /**a cached list of the loaded chunks*/
    public List<ChunkCoordIntPair> chunkLocations = new ArrayList<>();
    /**The X velocity of the front bogie*/
    public double frontVelocityX=0;
    /**The Z velocity of the front bogie*/
    public double frontVelocityZ=0;
    /**The X velocity of the back bogie*/
    public double backVelocityX=0;
    /**The Z velocity of the back bogie*/
    public double backVelocityZ=0;
    /**Used to be sure we only say once that the transport has been derailed*/
    private boolean displayDerail = false;
    public HitboxDynamic collisionHandler=null;
    /**/
    float prevRotationRoll;
    /**/
    float rotationRoll;
    public int forceBackupTimer =0;
    public float pullingWeight=0;
    public boolean onVanillaRails=false;

    //@SideOnly(Side.CLIENT)
    public TransportRenderData renderData = new TransportRenderData();

    /**the array of booleans, defined as bits
     * 0- brake: defines the brake
     * 1- locked: defines if transport is locked to owner and key holders
     * 2- lamp: defines if lamp is on
     * 3- creative: defines if the transport should consume fuels and be able to derail.
     * 4- coupling: defines if the transport is looking to couple with other transports.
     * 5- inventory whitelist: defines if the inventory is a whitelist
     * 6- running: defines if te transport is running (usually only on trains).
     * 7-15 are unused.
     * for use see
     * @see #getBoolean(boolValues)
     * @see #setBoolean(boolValues, boolean)
     */
    private BitList bools = new BitList();

    public enum boolValues{BRAKE(0), LOCKED(1), LAMP(2), CREATIVE(3), COUPLINGFRONT(4), COUPLINGBACK(5), WHITELIST(6), RUNNING(7), @Deprecated DERAILED(8);
        public int index;
        boolValues(int index){this.index = index;}
    }

    public boolean getBoolean(boolValues var){
        if(worldObj.isRemote) {
            return bools.getFromInt(var.index, this.dataWatcher.getWatchableObjectInt(17));
        } else {
            return bools.get(var.index);
        }
    }

    public void setBoolean(boolValues var, boolean value){
        if (getBoolean(var) != value) {
            bools.set(var.index, value);
            updateWatchers = true;
        }
    }

    public GenericRailTransport(World world){
        super(world);
        setSize(0.25f,0.25f);
        ignoreFrustumCheck = true;
        inventory = new ArrayList<>();
        initInventorySlots();
        if(world!=null) {
            this.height = 0.25f;
            collisionHandler = new HitboxDynamic(getHitboxSize()[0],getHitboxSize()[1],getHitboxSize()[2]);
            collisionHandler.position(posX, posY, posZ, rotationPitch, rotationYaw);
        }
    }
    public GenericRailTransport(UUID owner, World world, double xPos, double yPos, double zPos){
        super(world);

        posY = yPos;
        posX = xPos;
        posZ = zPos;
        this.owner = owner;
        setSize(0.25f,0.25f);
        ignoreFrustumCheck = true;
        inventory = new ArrayList<>();
        initInventorySlots();
        if(world!=null) {
            this.height = 0.25f;
            collisionHandler = new HitboxDynamic(getHitboxSize()[0],getHitboxSize()[1],getHitboxSize()[2]);
            collisionHandler.position(posX, posY, posZ, rotationPitch, rotationYaw);
        }
    }

    /**
     * <h2>Entity initialization</h2>
     * Entity init runs right before the first tick.
     * This is useful for registering the datawatchers and inventory before we actually use them.
     * NOTE: slot 0 and 1 of the datawatcher are used by vanilla. It may be wise to leave the first 5 empty.
     */
    @Override
    public void entityInit(){
        this.dataWatcher.addObject(13, 0);//train fuel consumption current
        this.dataWatcher.addObject(20, "");//fluid tank data
        this.dataWatcher.addObject(15, 0);//train heat
        this.dataWatcher.addObject(16, 40.0f);//train heat
        this.dataWatcher.addObject(17, bools!=null?bools.toInt():BitList.newInt());//booleans
        //18 is used by EntityTrainCore
        //19 is used by the core minecart
        this.dataWatcher.addObject(23, "");//owner
        this.dataWatcher.addObject(21, 0);//front linked transport
        this.dataWatcher.addObject(22, 0);//back linked transport
        this.dataWatcher.addObject(24,getDefaultSkin());//currently used
    }

    /**
     * override this to customize the inventory slots.
     * call this in the override if you just want to add more slots to the existing planned inventory size
     */
    public void initInventorySlots(){
        if (getInventoryRows()>0) {
            int index=40;
            for(int r = 0; r< getInventoryRows(); r++){
                for (int c=0;c<9;c++){
                    inventory.add(new ItemStackSlot(this, index, -97 + (c * 18), -19 + (r * 18) + ((int)((11 - getInventoryRows()) * 0.5f) * 18)));
                    index++;
                }
            }
        }
    }

    public ItemStackSlot fuelSlot(){
        return new ItemStackSlot(this, 400,114,32);

    }
    public ItemStackSlot waterSlot(){
        return new ItemStackSlot(this, 401,150,32).setOverlay(Items.water_bucket);
    }

    public ItemStackSlot tankerInputSlot(){
        return new ItemStackSlot(this, 401,150,-8).setOverlay(Items.water_bucket);
    }
    public ItemStackSlot tankerOutputSlot(){
        return new ItemStackSlot(this, 401,150,32).setOverlay(Items.bucket);
    }

    /**
     * use this if you plan to implement a custom Gui and Container in your own client/common proxy.
     */
    public boolean hasCustomGUI(){
        return false;
    }

    /*
     * <h2>base entity overrides</h2>
     * modify basic entity variables to give different uses/values.
     */
    /**returns if the player can push this, we actually use our own systems for this, so we return false*/
    @Override
    public boolean canBePushed() {return false;}
    @Override
    public int getMinecartType(){return 10002;}
    /**returns the hitbox of this entity, we dont need that so return null*/
    @Override
    public AxisAlignedBB getBoundingBox(){
        return null;}
    /**returns the hitbox of this entity, we dont need that so return null*/
    @Override
    public AxisAlignedBB getCollisionBox(Entity collidedWith){
        return null;}
    /**returns if this can be collided with, we don't use this so return false*/
    @Override
    public boolean canBeCollidedWith() {return true;}
    /**client only positioning of the transport, this should help to smooth the movement*/
    @SideOnly(Side.CLIENT)
    public void setPositionAndRotation2(double p_70056_1_, double p_70056_3_, double p_70056_5_, float p_70056_7_, float p_70056_8_, int p_70056_9_) {
        if (frontBogie!=null && backBogie!= null){

            setRotation((float)Math.toDegrees(RailUtility.atan2f(
                    frontBogie.posZ - backBogie.posZ,
                    frontBogie.posX - backBogie.posX)),
                    MathHelper.floor_double(Math.acos(frontBogie.posY / backBogie.posY)*RailUtility.degreesD));

            //base the position from the front bogie and it's offset
            vectorCache[5] = RailUtility.rotatePointF(-this.bogieLengthFromCenter()[0],0,0,this.rotationPitch, this.rotationYaw,0);
            //position this
            setPosition(vectorCache[5][0]+frontBogie.posX,vectorCache[5][1]+frontBogie.posY,vectorCache[5][2]+frontBogie.posZ);

            updateRiderPosition();
        }else {
            this.setPosition(p_70056_1_, p_70056_3_, p_70056_5_);
        }
        this.setRotation(p_70056_7_, p_70056_8_);
    }

    @Override
    public void moveEntity(double p_70091_1_, double p_70091_3_, double p_70091_5_){
        DebugUtil.println("this is actually used???");
        super.moveEntity(p_70091_1_,p_70091_3_, p_70091_5_);
    }

    @Override
    public boolean interactFirst(EntityPlayer p_130002_1_) {
        return worldObj.isRemote?interact(p_130002_1_.getEntityId(),false,false, -1):super.interactFirst(p_130002_1_);
    }

    public boolean interact(int player, boolean isFront, boolean isBack, int key) {
        EntityPlayer p =((EntityPlayer)worldObj.getEntityByID(player));
        if (worldObj.isRemote) {
            if (p.getHeldItem()!=null && p.getHeldItem().getItem() instanceof ItemPaintBucket) {
                p.openGui(TrainsInMotion.instance, getEntityId(), worldObj, 0, 0, 0);
                return true;
            }
            TrainsInMotion.keyChannel.sendToServer(new PacketInteract(key, getEntityId()));
        } else {
            //check if the player has permission first.
            if (!getPermissions(p, false, false)) {
                p.addChatMessage(new ChatComponentText(RailUtility.translate("You don't have permission to do that.")));
                return false;
            }
            switch (key){
                case -999:{//entity attacked
                    p.attackTargetEntityWithCurrentItem(this);
                    break;
                }
                case -1: {//right click
                    if (p.getHeldItem() != null) {
                        if (p.getHeldItem().getItem() instanceof ItemKey) {
                            if (ItemKey.getHostList(p.getHeldItem()) !=null) {
                                for (UUID transport : ItemKey.getHostList(p.getHeldItem())) {
                                    if (transport.equals(getPersistentID())) {
                                        return true;//end the function here if it already has the key.
                                    }
                                }
                            }
                            if(((ItemKey) p.getHeldItem().getItem()).selectedEntity ==null || ((ItemKey) p.getHeldItem().getItem()).selectedEntity != getEntityId()){
                                ((ItemKey) p.getHeldItem().getItem()).selectedEntity = getEntityId();
                                p.addChatComponentMessage(new ChatComponentText(
                                        RailUtility.translate("Click again to add the ") + transportName() +
                                                RailUtility.translate(" to the Item's list.")

                                ));
                                return true;//end the function here if it already has the key.
                            } else {
                                ItemKey.addHost(p.getHeldItem(), getPersistentID(), transportName());
                                p.addChatComponentMessage(new ChatComponentText(
                                        RailUtility.translate("added ") + transportName() +
                                                RailUtility.translate(" to the Item's list.")

                                ));
                                ((ItemKey) p.getHeldItem().getItem()).selectedEntity=null;
                                return true;//end the function here if it already has the key.
                            }
                        }
                        //TODO: else if(player.getHeldItem() instanceof stakeItem) {do linking/unlinking stuff dependant on if it was front or not;}
                    }
                    //be sure the player has permission to enter the transport, and that the transport has the main seat open.
                    if (getRiderOffsets() != null && riddenByEntity == null && getPermissions(p, false, true)) {
                        p.mountEntity(this);
                        return true;
                        //if the player had permission but the main seat isnt open, check for seat entities to mount.
                    } else {
                        for (EntitySeat seat : seats) {
                            if (seat.riddenByEntity == null && getPermissions(p, false, true)) {
                                p.mountEntity(seat);
                                return true;
                            }
                        }
                    }
                }
                case 1:{ //open GUI
                    p.openGui(TrainsInMotion.instance, getEntityId(), worldObj, 0, (int)posY, 0);
                    return true;
                }case 15: {//toggle brake
                    setBoolean(boolValues.BRAKE, !getBoolean(boolValues.BRAKE));
                    return true;
                }case 5: { //Toggle lamp
                    setBoolean(boolValues.LAMP, !getBoolean(boolValues.LAMP));
                    return true;
                }case 6:{ //Toggle locked
                    setBoolean(boolValues.LOCKED, !getBoolean(boolValues.LOCKED));
                    return true;
                }case 10:{ //Toggle transport creative mode
                    setBoolean(boolValues.CREATIVE, !getBoolean(boolValues.CREATIVE));
                    return true;
                }case 7:{ //Toggle coupling for both ends
                    boolean toset = !getBoolean(boolValues.COUPLINGFRONT);
                    setBoolean(boolValues.COUPLINGFRONT, toset);
                    setBoolean(boolValues.COUPLINGBACK, toset);
                    return true;
                }case 13:{ //unlink transports
                    GenericRailTransport transport;
                    //frontLinkedTransport
                    if (frontLinkedID != null){
                        transport = (worldObj.getEntityByID(frontLinkedID) instanceof GenericRailTransport)?(GenericRailTransport) worldObj.getEntityByID(frontLinkedID):null;
                        if (transport != null){
                            if(transport.frontLinkedID == this.getEntityId()){
                                transport.frontLinkedTransport = null;
                                transport.frontLinkedID = null;
                            } else {
                                transport.backLinkedTransport = null;
                                transport.backLinkedID = null;
                            }
                            frontLinkedTransport = null;
                            frontLinkedID = null;
                            transport.updateWatchers = true;
                        }
                    }
                    //backLinkedTransport
                    if (backLinkedID != null){
                        transport = (worldObj.getEntityByID(backLinkedID) instanceof GenericRailTransport)?(GenericRailTransport) worldObj.getEntityByID(backLinkedID):null;
                        if (transport != null){
                            if(transport.frontLinkedID == this.getEntityId()){
                                transport.frontLinkedTransport = null;
                                transport.frontLinkedID = null;
                            } else {
                                transport.backLinkedTransport = null;
                                transport.backLinkedID = null;
                            }
                            backLinkedTransport = null;
                            backLinkedID = null;
                            transport.updateWatchers = true;
                        }
                    }
                    return true;
                }
            }
        }

        return super.interactFirst((EntityPlayer) worldObj.getEntityByID(player));

    }

    /**
     * <h2>damage and destruction</h2>
     * attackEntityFromPart is called when one of the hitboxes of the entity has taken damage of some form.
     * the damage done is handled manually so we can compensate for basically everything, and if health is 0 or lower, we destroy the entity part by part, leaving the main part of the entity for last.
     */
    @Override
    public boolean attackEntityFrom(DamageSource damageSource, float p_70097_2_){


        if (damageSource.getEntity() instanceof GenericRailTransport){
            return false;
        }
        //if its a creative player, destroy instantly
        if (damageSource.getEntity() instanceof EntityPlayer && ((EntityPlayer) damageSource.getEntity()).capabilities.isCreativeMode && !damageSource.isProjectile()){
            health -=20;
            //if its reinforced and its not an explosion
        } else if (isReinforced() && !damageSource.isProjectile() && !damageSource.isExplosion()){
            health -=1;
            //if it is an explosion and it's reinforced, or it's not an explosion and isn't reinforced
        } else if ((damageSource.isExplosion() && isReinforced()) || (!isReinforced() && !damageSource.isProjectile())){
            health -=5;
            //if it isn't reinforced and is an explosion
        } else if (damageSource.isExplosion() && !isReinforced()){
            health-=20;
        }
        //cover overheating, or other damage to self.
        if (damageSource.getSourceOfDamage() == this){
            health-=20;
        }

        //on Destruction
        if (health<1){
            //remove this
            if (damageSource.getEntity() instanceof EntityPlayer) {
                TrainsInMotion.keyChannel.sendToServer(new PacketRemove(getEntityId(), !((EntityPlayer) damageSource.getEntity()).capabilities.isCreativeMode));
            } else {
                TrainsInMotion.keyChannel.sendToServer(new PacketRemove(getEntityId(),false));
            }
            setDead();
            return true;
        }
        return false;
    }

    public void setDead() {
        super.setDead();
        //remove bogies
        if (frontBogie != null) {
            frontBogie.setDead();
            TrainsInMotion.keyChannel.sendToServer(new PacketRemove(frontBogie.getEntityId(), false));
            worldObj.removeEntity(frontBogie);
        }
        if (backBogie != null) {
            backBogie.setDead();
            TrainsInMotion.keyChannel.sendToServer(new PacketRemove(backBogie.getEntityId(), false));
            worldObj.removeEntity(backBogie);
        }
        //remove seats
        for (EntitySeat seat : seats) {
            seat.setDead();
            TrainsInMotion.keyChannel.sendToServer(new PacketRemove(seat.getEntityId(),false));
            seat.worldObj.removeEntity(seat);
        }
        //be sure the front and back links are removed in the case of this entity being removed from the world.
        if (frontLinkedID != null){
            GenericRailTransport front = ((GenericRailTransport)worldObj.getEntityByID(frontLinkedID));
            if(front != null && front.frontLinkedID != null && front.frontLinkedID == this.getEntityId()){
                front.frontLinkedID = null;
                front.frontLinkedTransport = null;
            } else if(front != null && front.backLinkedID != null && front.backLinkedID == this.getEntityId()){
                front.backLinkedID = null;
                front.backLinkedTransport = null;
            }
        }
        if (backLinkedID != null){
            GenericRailTransport back = ((GenericRailTransport)worldObj.getEntityByID(backLinkedID));
            if(back != null && back.frontLinkedID != null && back.frontLinkedID == this.getEntityId()){
                back.frontLinkedID = null;
                back.frontLinkedTransport = null;
            } else if(back != null && back.backLinkedID != null && back.backLinkedID == this.getEntityId()){
                back.backLinkedID = null;
                back.backLinkedTransport = null;
            }
        }

    }

    /*
     * <h3>add bogies and seats</h3>
     */
    /** this is called by the seats and seats on their spawn to add them to this entity's list of seats, we only do it on client because that's the only side that seems to lose track.
     * @see EntitySeat#readSpawnData(ByteBuf)*/
    @SideOnly(Side.CLIENT)
    public void setseats(EntitySeat seat, int seatNumber){
        if (seats.size() <= seatNumber) {
            seats.add(seat);
        } else {
            seats.set(seatNumber, seat);
        }
    }

    /** this is called by the bogies on their spawn to add them to this entity's list of bogies, we only do it on client because that's the only side that seems to lose track.
     * @see EntityBogie#readSpawnData(ByteBuf)*/
    @SideOnly(Side.CLIENT)
    public void setBogie(EntityBogie cart, boolean isFront){
        if(isFront){
            frontBogie = cart;
        } else {
            backBogie = cart;
        }
    }

    /*
     * <h2> Data Syncing and Saving </h2>
     * SpawnData is mainly used for data that has to be created on client then sent to the server, like data processed on item use.
     * NBT is save data, which only happens on server.
     */

    /**reads the data sent from client on entity spawn*/
    @Deprecated //todo: send this data over the datawatcher or other more reliable means
    @Override
    public void readSpawnData(ByteBuf additionalData) {
        owner = new UUID(additionalData.readLong(), additionalData.readLong());
        rotationYaw = additionalData.readFloat();
    }
    @Deprecated //todo: send this data over the datawatcher or other more reliable means
    /**sends the data to server from client*/
    @Override
    public void writeSpawnData(ByteBuf buffer) {
        buffer.writeLong(owner.getMostSignificantBits());
        buffer.writeLong(owner.getLeastSignificantBits());
        buffer.writeFloat(rotationYaw);
    }
    /**loads the entity's save file*/
    @Override
    protected void readEntityFromNBT(NBTTagCompound tag) {
        bools.set(tag.getInteger(NBTKeys.bools));
        isDead = tag.getBoolean(NBTKeys.dead);
        //load links
        if (tag.hasKey(NBTKeys.frontLinkMost)) {
            frontLinkedTransport = new UUID(tag.getLong(NBTKeys.frontLinkMost), tag.getLong(NBTKeys.frontLinkLeast));
        }
        if (tag.hasKey(NBTKeys.backLinkMost)) {
            backLinkedTransport = new UUID(tag.getLong(NBTKeys.backLinkMost), tag.getLong(NBTKeys.backLinkLeast));
        }
        //load owner
        owner = new UUID(tag.getLong(NBTKeys.ownerMost),tag.getLong(NBTKeys.ownerLeast));
        ownerName = tag.getString(NBTKeys.ownerName);

        String skin = tag.getString(NBTKeys.skinURI);
        if(SkinRegistry.getSkin(this, null, false,skin)!=null) {
            dataWatcher.updateObject(24, skin);
        } else {
            dataWatcher.updateObject(24, this.getDefaultSkin());
        }


        //load bogie velocities
        frontVelocityX = tag.getDouble(NBTKeys.frontBogieX);
        frontVelocityZ = tag.getDouble(NBTKeys.frontBogieZ);
        backVelocityX = tag.getDouble(NBTKeys.backBogieX);
        backVelocityZ = tag.getDouble(NBTKeys.backBogieZ);

        rotationRoll = tag.getFloat(NBTKeys.rotationRoll);
        prevRotationRoll = tag.getFloat(NBTKeys.prevRotationRoll);

        if(getTankCapacity()!=null) {
            fluidTank = new FluidTankInfo[getTankCapacity().length];
            for (int i = 0; i < getTankCapacity().length; i++) {
                if (tag.hasKey("tanks." + i)) {
                    fluidTank[i] = new FluidTankInfo(FluidStack.loadFluidStackFromNBT(tag.getCompoundTag("tanks." + i)), getTankCapacity()[i]);
                }
            }
        } else {
            fluidTank= null;
        }

        inventory = new ArrayList<>();
        initInventorySlots();

        NBTTagCompound invTag;

        if (getSizeInventory()>0) {
            for (int i=0;i<getSizeInventory();i++) {
                invTag = tag.getCompoundTag("transportinv."+i);
                if (invTag!=null) {
                    inventory.get(i).setSlotContents(ItemStack.loadItemStackFromNBT(invTag),inventory);
                }
            }
        }

        updateWatchers = true;
    }
    /**saves the entity to server world*/
    @Override
    protected void writeEntityToNBT(NBTTagCompound tag) {
        tag.setInteger(NBTKeys.bools, bools.toInt());
        tag.setBoolean(NBTKeys.dead, isDead);
        //frontLinkedTransport and backLinkedTransport bogies
        if (frontLinkedTransport != null){
            tag.setLong(NBTKeys.frontLinkMost, frontLinkedTransport.getMostSignificantBits());
            tag.setLong(NBTKeys.frontLinkLeast, frontLinkedTransport.getLeastSignificantBits());
        }
        if (backLinkedTransport != null){
            tag.setLong(NBTKeys.backLinkMost, backLinkedTransport.getMostSignificantBits());
            tag.setLong(NBTKeys.backLinkLeast, backLinkedTransport.getLeastSignificantBits());
        }
        //owner
        tag.setLong(NBTKeys.ownerMost, owner.getMostSignificantBits());
        tag.setLong(NBTKeys.ownerLeast, owner.getLeastSignificantBits());
        tag.setString(NBTKeys.ownerName, ownerName);


        tag.setString(NBTKeys.skinURI, dataWatcher.getWatchableObjectString(24));

        //bogie velocities
        tag.setDouble(NBTKeys.frontBogieX, frontVelocityX);
        tag.setDouble(NBTKeys.frontBogieZ, frontVelocityZ);
        tag.setDouble(NBTKeys.backBogieX, backVelocityX);
        tag.setDouble(NBTKeys.backBogieZ, backVelocityZ);

        tag.setFloat(NBTKeys.rotationRoll, rotationRoll);
        tag.setFloat(NBTKeys.prevRotationRoll, prevRotationRoll);


        for(int i=0; i<getTankInfo(null).length;i++){
            if(getTankInfo(null) !=null) {
                NBTTagCompound tank = new NBTTagCompound();
                getTankInfo(null)[i].fluid.writeToNBT(tank);
                tag.setTag("tanks." + i, tank);
            }
        }
        NBTTagCompound invTag;

        if (inventory!=null) {
            for (int i=0;i<getSizeInventory();i++) {
                invTag = new NBTTagCompound();
                if(inventory.get(i)!=null && inventory.get(i).getStack()!=null) {
                    inventory.get(i).getStack().writeToNBT(invTag);
                }
                tag.setTag("transportinv."+i, invTag);
            }
        }

    }

    @Override
    public boolean writeMountToNBT(NBTTagCompound tag){
        return false;
    }

    /**todo: plays a sound during entity movement*/
    @Override
    protected void func_145780_a(int p_145780_1_, int p_145780_2_, int p_145780_3_, Block p_145780_4_) {}

    public boolean hasDrag(){return true;}

    public void updatePosition(){
        frontBogie.minecartMove(this, hasDrag(), getBoolean(boolValues.BRAKE),
                weightKg() * (frontBogie.isOnSlope?1.5f:1) * (backBogie.isOnSlope?2:1));
        backBogie.minecartMove(this, hasDrag(), getBoolean(boolValues.BRAKE),
                weightKg() * (frontBogie.isOnSlope?1.5f:1) * (backBogie.isOnSlope?2:1));
        motionX = frontVelocityX = frontBogie.motionX;
        motionZ = frontVelocityZ = frontBogie.motionZ;
        backVelocityX = backBogie.motionX;
        backVelocityZ = backBogie.motionZ;


        setRotation((RailUtility.atan2degreesf(
                frontBogie.posZ - backBogie.posZ,
                frontBogie.posX - backBogie.posX)),
                MathHelper.floor_double(Math.acos(frontBogie.posY / backBogie.posY)*RailUtility.degreesD),
                rotationYaw-prevRotationYaw);

        //base the position from the front bogie and it's offset
        vectorCache[5] = RailUtility.rotatePointF(-this.bogieLengthFromCenter()[0],0,0,this.rotationPitch, this.rotationYaw,0);
        //position this
        setPosition(vectorCache[5][0]+frontBogie.posX,vectorCache[5][1]+frontBogie.posY,vectorCache[5][2]+frontBogie.posZ);
        collisionHandler.position(posX, posY, posZ, rotationPitch, rotationYaw);

    }



    /**
     * <h2> on entity update </h2>
     *
     * defines what should be done every tick
     * used for:
     * managing the list of bogies and seats, respawning them if they disappear.
     * managing speed, acceleration. and direction.
     * managing rotationYaw and rotationPitch.
     * updating rider entity positions if there is no one riding the core seat.
     * calling on link management.
     * @see #manageLinks(GenericRailTransport)
     * syncing the owner entity ID with client.
     * and updating the lighting block.
     */
    @Override
    public void onUpdate() {
        if (!worldObj.isRemote) {
            if (forceBackupTimer > 0) {
                forceBackupTimer--;
            } else if (forceBackupTimer == 0) {
                ServerLogger.writeWagonToFolder(this);
                forceBackupTimer--;
            }
        }

        //regen health after a while
        if(health<20 && ticksExisted%40==0){
            if(health>15){
                health=20;
            } else {
                health+=5;
            }
        }

        //if the cart has fallen out of the map, destroy it.
        if (posY < -64.0D & isDead){
            worldObj.removeEntity(this);
        }

        if(this.chunkTicket == null) {
            this.requestTicket();
        }

        //be sure bogies exist

        //always be sure the bogies exist on client and server.
        if (!worldObj.isRemote && (frontBogie == null || backBogie == null)) {
            //spawn frontLinkedTransport bogie
            vectorCache[0] = RailUtility.rotatePointF(bogieLengthFromCenter()[0], 0, 0,rotationPitch, rotationYaw,0);
            frontBogie = new EntityBogie(worldObj, posX + vectorCache[0][0], posY + vectorCache[0][1], posZ + vectorCache[0][2], getEntityId(), true);
            frontBogie.setVelocity(frontVelocityX,0,frontVelocityZ);
            //spawn backLinkedTransport bogie
            vectorCache[0] = RailUtility.rotatePointF(bogieLengthFromCenter()[1], 0, 0, rotationPitch, rotationYaw,0);
            backBogie = new EntityBogie(worldObj, posX + vectorCache[0][0], posY + vectorCache[0][1], posZ + vectorCache[0][2], getEntityId(), false);
            backBogie.setVelocity(backVelocityX, 0, backVelocityZ);
            worldObj.spawnEntityInWorld(frontBogie);
            worldObj.spawnEntityInWorld(backBogie);

            if (getRiderOffsets() != null && getRiderOffsets().length >1 && seats.size()<getRiderOffsets().length) {
                for (int i = 0; i < getRiderOffsets().length - 1; i++) {
                    EntitySeat seat = new EntitySeat(worldObj, posX, posY, posZ, getRiderOffsets()[i][0], getRiderOffsets()[i][1],getRiderOffsets()[i][2], getEntityId(), i);
                    worldObj.spawnEntityInWorld(seat);
                    seats.add(seat);
                }
            }
        }

        /*
         * run the hitbox check whether or not the bogies exist so we can ensure interaction even during severe client-sided error.
         *check if the bogies exist, because they may not yet, and if they do, check if they are actually moving or colliding.
         * no point in processing movement if they aren't moving or if the train hit something.
         * if it is clear however, then we need to add velocity to the bogies based on the current state of the train's speed and fuel, and reposition the train.
         * but either way we have to position the bogies around the train, just to be sure they don't accidentally fly off at some point.
         *
         * this stops updating if the transport derails. Why update positions of something that doesn't move? We compensate for first tick to be sure hitboxes, bogies, etc, spawn on join.
         */
        if (frontBogie!=null && backBogie != null && (!getBoolean(boolValues.DERAILED) || ticksExisted==1)){
            //handle movement.
            if (!worldObj.isRemote) {
                if (frontLinkedID != null && worldObj.getEntityByID(frontLinkedID) instanceof GenericRailTransport) {
                    manageLinks((GenericRailTransport) worldObj.getEntityByID(frontLinkedID));
                }
                if (backLinkedID != null && worldObj.getEntityByID(backLinkedID) instanceof GenericRailTransport) {
                    manageLinks((GenericRailTransport) worldObj.getEntityByID(backLinkedID));
                }
            }
            updatePosition();


            if(ticksExisted%5==0) {
                double[] v = RailUtility.rotatePoint(bogieLengthFromCenter()[0], 0, rotationYaw);
                frontBogie.addVelocity(
                        ((backBogie.posX - posX)-v[0])*0.001,
                        0,
                        ((backBogie.posZ - posZ)-v[2])*0.001);
                v = RailUtility.rotatePoint(bogieLengthFromCenter()[1], 0, rotationYaw);
                frontBogie.addVelocity(
                        ((frontBogie.posX - posX)-v[0])*0.001,
                        0,
                        ((frontBogie.posZ - posZ)-v[2])*0.001);
            }
        }

        //rider updating isn't called if there's no driver/conductor, so just in case of that, we reposition the seats here too.
        if (riddenByEntity == null && getRiderOffsets() != null) {
            for (int i = 0; i < seats.size(); i++) {
                vectorCache[0] = rotatePointF(getRiderOffsets()[i][0], getRiderOffsets()[i][1], getRiderOffsets()[i][2], rotationPitch, rotationYaw, 0f);
                vectorCache[0][0] += posX;
                vectorCache[0][1] += posY;
                vectorCache[0][2] += posZ;
                seats.get(i).setPosition(vectorCache[0][0], vectorCache[0][1], vectorCache[0][2]);
            }
        }

        //be sure the owner entityID is currently loaded, this variable is dynamic so we don't save it to NBT.
        if (!worldObj.isRemote &&ticksExisted %10==0){

            manageFuel();


            if (ownerName.equals("")) {
                @Nullable
                Entity player = CommonProxy.getEntityFromUuid(owner);
                if (player instanceof EntityPlayer) {
                    if (!ownerName.equals(((EntityPlayer) player).getDisplayName())) {
                        ownerName = ((EntityPlayer) player).getDisplayName();
                        updateWatchers = true;
                    }
                }
            }
            //sync the linked transports with client, and on server, easier to use an ID than a UUID.
            Entity linkedTransport = CommonProxy.getEntityFromUuid(frontLinkedTransport);
            if (linkedTransport instanceof GenericRailTransport && (frontLinkedID == null || linkedTransport.getEntityId() != frontLinkedID)) {
                frontLinkedID = linkedTransport.getEntityId();
                updateWatchers = true;
            }
            linkedTransport = CommonProxy.getEntityFromUuid(backLinkedTransport);
            if (linkedTransport instanceof GenericRailTransport && (backLinkedID == null || linkedTransport.getEntityId() != backLinkedID)) {
                backLinkedID = linkedTransport.getEntityId();
                updateWatchers = true;
            }

            if (!worldObj.isRemote && getBoolean(boolValues.DERAILED) && !displayDerail){
                //todo
                //MinecraftServer.getServer().addChatMessage(new ChatComponentText(getOwner().getName()+"'s " + StatCollector.translateToLocal(getItem().getUnlocalizedName()) + " has derailed!"));
                displayDerail = true;
            }

            if(updateWatchers){
                if(getTankCapacity()!=null) {
                    StringBuilder tanks = new StringBuilder();
                    for (int i = 0; i < getTankCapacity().length; i++) {
                        //todo: these should NEVER be null
                        if (getTankInfo(null)[i] != null && getTankInfo(null)[i].fluid != null) {
                            tanks.append(getTankInfo(null)[i].fluid.amount);
                            tanks.append(",");
                            tanks.append(getTankInfo(null)[i].fluid.getFluid().getName());
                            tanks.append(";");
                        } else {
                            tanks.append(0);
                            tanks.append(",");
                            tanks.append(FluidRegistry.WATER.getName());
                            tanks.append(";");
                        }
                    }

                    this.dataWatcher.updateObject(20, tanks.toString());
                }
                this.dataWatcher.updateObject(23, ownerName);
                this.dataWatcher.updateObject(17, bools.toInt());
                this.dataWatcher.updateObject(21, frontLinkedID!=null?frontLinkedID:-1);
                this.dataWatcher.updateObject(22, backLinkedID!=null?backLinkedID:-1);
            }
        }

        //handle collisions
        if (worldObj.isRemote){
            for (Entity e : collisionHandler.getCollidingPlayers(this)) {
                double d0 = e.posX - this.posX;
                double d1 = e.posZ - this.posZ;
                double d2 = MathHelper.abs_max(d0, d1) * 1.25d;

                //push entity away
                if (d2 >= 0.009999999776482582D) {
                    d2 = MathHelper.sqrt_double(d2);
                    d0 /= d2;
                    d1 /= d2;

                    d0 *= Math.min(1.0D / d2, 1D);
                    d1 *= Math.min(1.0D / d2, 1D);
                    d0 *= 0.05000000074505806D;
                    d1 *= 0.05000000074505806D;
                    d0 *= (1.0D - this.entityCollisionReduction);
                    d1 *= (1.0D - this.entityCollisionReduction);
                    e.addVelocity(d0*2.5, 0.0D, d1*2.5);
                    e.setSprinting(false);
                }
            }
        } else {
            for (Entity e : collisionHandler.getCollidingEntities(this)) {
                if (e.ridingEntity != null) {
                    continue;
                }
                if (e instanceof EntityItem) {
                    if (getTypes().contains(TrainsInMotion.transportTypes.HOPPER) && this.posY > this.posY + 0.5f &&
                            ((EntityItem) e).getEntityItem()!=null && isItemValidForSlot(0, ((EntityItem) e).getEntityItem())) {
                        addItem(((EntityItem) e).getEntityItem());
                        worldObj.removeEntity(e);
                    }

                } else if (e instanceof GenericRailTransport) {
                    double d0 = e.posX - this.posX;
                    double d1 = e.posZ - this.posZ;
                    double d2 = MathHelper.abs_max(d0, d1) * 1.25d;

                    //push entity away
                    if (d2 >= 0.009999999776482582D) {
                        d2 = MathHelper.sqrt_double(d2);
                        d0 /= d2;
                        d1 /= d2;

                        d0 *= Math.min(1.0D / d2, 1D);
                        d1 *= Math.min(1.0D / d2, 1D);
                        d0 *= 0.05000000074505806D;
                        d1 *= 0.05000000074505806D;
                        d0 *= (1.0D - this.entityCollisionReduction);
                        d1 *= (1.0D - this.entityCollisionReduction);
                        //this.motionX=0;
                        //this.motionZ=0;
                        this.backBogie.addVelocity(-d0 * 0.5f, 0, -d1 * 0.5f);
                        this.frontBogie.addVelocity(-d0 * 0.5f, 0, -d1 * 0.5f);
                    }
                } else if (e instanceof EntityLiving || e instanceof EntityPlayer) {
                    double d0 = e.posX - this.posX;
                    double d1 = e.posZ - this.posZ;
                    double d2 = MathHelper.abs_max(d0, d1) * 1.25d;

                    //push entity away
                    if (d2 >= 0.009999999776482582D) {
                        d2 = MathHelper.sqrt_double(d2);
                        d0 /= d2;
                        d1 /= d2;

                        d0 *= Math.min(1.0D / d2, 1D);
                        d1 *= Math.min(1.0D / d2, 1D);
                        d0 *= 0.05000000074505806D;
                        d1 *= 0.05000000074505806D;
                        if (e instanceof EntityPlayer && !getBoolean(boolValues.BRAKE)) {
                            this.frontBogie.addVelocity(-d0 * 1.15f, 0, -d1 * 1.15f);
                            this.backBogie.addVelocity(-d0 * 1.15f, 0, -d1 * 1.15f);
                        }
                    }
                    //hurt entity if going fast
                    if (Math.abs(motionX) + Math.abs(motionZ) > 0.25f) {
                        e.attackEntityFrom(new EntityDamageSource(
                                        this instanceof EntityTrainCore ? "Locomotive" : "rollingstock", this),
                                (float) (motionX + motionZ) * 0.1f);
                    }
                }
            }
        }
        //handle particles
        if (backBogie!=null && !isDead && worldObj.isRemote) {
            if (ClientProxy.EnableParticles || getParticles().size()>0) {
                ParticleFX.updateParticleItterator(getParticles(), getBoolean(boolValues.RUNNING));
            }
        }
    }


    /**
     * iterates all the links to check if the stock has a train
     */
    public void updateConsist(){
        List<GenericRailTransport> transports = new ArrayList<>();
        transports.add(this);

        //check the front, then loop for every transport linked to it in opposite direction of this.
        GenericRailTransport link = frontLinkedID==null?null:(GenericRailTransport) worldObj.getEntityByID(frontLinkedID);
        while(link!=null && !transports.contains(link)){
            transports.add(link);
            link = link.frontLinkedID==null?null:(GenericRailTransport) worldObj.getEntityByID(link.frontLinkedID);
        }
        //do it again, but for the back one
        link= backLinkedID==null?null:(GenericRailTransport) worldObj.getEntityByID(backLinkedID);
        while(link!=null && !transports.contains(link)){
            transports.add(link);
            link = link.backLinkedID==null?null:(GenericRailTransport) worldObj.getEntityByID(link.backLinkedID);
        }

        //now tell everything in the list, including this, that there's a new list, and provide said list.
        for(GenericRailTransport t : transports){
            t.setValuesOnLinkUpdate(transports);
        }
    }


    //todo: update needed info like weight and combined tractive effort based on values in this array
    public void setValuesOnLinkUpdate(List<GenericRailTransport> consist){

    }

    //used for trains and B-units
    public float getPower(){return 0;}


    /**
     * <h2>Rider offset</h2>
     * this runs every tick to be sure the riders, and seats, are in the correct positions.
     * NOTE: this only happens while there is an entity riding this, entities riding seats do not activate this function.
     */
    @Override
    public void updateRiderPosition() {
        if (getRiderOffsets() != null) {
            if (riddenByEntity != null) {
                vectorCache[2] = rotatePointF(getRiderOffsets()[0][0],getRiderOffsets()[0][1],getRiderOffsets()[0][2], rotationPitch, rotationYaw, 0);
                riddenByEntity.setPosition(vectorCache[2][0] + this.posX, vectorCache[2][1] + this.posY+(worldObj.isRemote?0:1)+(frontBogie==null?0:frontBogie.yOffset), vectorCache[2][2] + this.posZ);
            }

            for (int i = 0; i < seats.size(); i++) {
                vectorCache[2] = rotatePointF(getRiderOffsets()[i][0],getRiderOffsets()[i][1],getRiderOffsets()[i][2], rotationPitch, rotationYaw, 0);
                vectorCache[2][0] += posX;
                vectorCache[2][1] += posY+(worldObj.isRemote?0:1)+(frontBogie==null?0:frontBogie.yOffset);
                vectorCache[2][2] += posZ;
                seats.get(i).setPosition(vectorCache[2][0], vectorCache[2][1], vectorCache[2][2]);
            }
        }

    }


    /**
     * <h2>manage links</h2>
     * this is used to reposition the transport based on the linked transports.
     * If coupling is on then it will check sides without linked transports for anything to link to.
     */
    public void manageLinks(GenericRailTransport linkedTransport) {
        vectorCache[4][0]=(float)(linkedTransport.posX - this.posX);
        vectorCache[4][2]=(float)(linkedTransport.posZ - this.posZ);
        if (Math.abs(vectorCache[4][0]) + Math.abs(vectorCache[4][2]) >0.05f) {

            float distance = MathHelper.sqrt_double((vectorCache[4][0] * vectorCache[4][0]) + (vectorCache[4][2] * vectorCache[4][2]));

            vectorCache[5][0] = vectorCache[4][0] / distance;
            vectorCache[5][2] = vectorCache[4][2] / distance;

            if (linkedTransport.frontLinkedID != null && linkedTransport.frontLinkedID == this.getEntityId()) {
                distance -= Math.abs(linkedTransport.getHitboxSize()[0]*-0.5f);
            } else {
                distance -= Math.abs(linkedTransport.getHitboxSize()[0]*0.5f);
            }
            if (this.frontLinkedID != null && this.frontLinkedID == linkedTransport.getEntityId()) {
                distance -= Math.abs(this.getHitboxSize()[0]*-0.5f);
            } else {
                distance -= Math.abs(this.getHitboxSize()[0]*0.5f);
            }

            vectorCache[4][0] = 0.3f * distance * vectorCache[4][0];
            vectorCache[4][2] = 0.3f * distance * vectorCache[4][2];

            distance = (-vectorCache[4][0] - vectorCache[4][0]) * vectorCache[5][0] + (-vectorCache[4][2] - vectorCache[4][2]) * vectorCache[5][2];

            vectorCache[4][0] -= 0.4f * distance * vectorCache[5][0] * -1f;
            vectorCache[4][2] -= 0.4f * distance * vectorCache[5][2] * -1f;


            this.frontBogie.addVelocity(vectorCache[4][0], 0, vectorCache[4][2]);
            this.backBogie.addVelocity(vectorCache[4][0], 0, vectorCache[4][2]);
        }

    }


    /**
     * <h2>Permissions handler</h2>
     * Used to check if the player has permission to do whatever it is the player is trying to do. Yes I could be more vague with that.
     *
     * @param player the player attenpting to interact.
     * @param driverOnly can this action only be done by the driver/conductor?
     * @return if the player has permission to continue
     */
    public boolean getPermissions(EntityPlayer player, boolean driverOnly, boolean decreaseTicketStack) {
        //if this requires the player to be the driver, and they aren't, just return false before we even go any further.
        if (player ==null || (driverOnly && riddenByEntity!=null && player.getEntityId() != this.riddenByEntity.getEntityId())){
            return false;
        }

        //be sure operators and owners can do whatever
        if ((player.capabilities.isCreativeMode && player.canCommandSenderUseCommand(2, "")) || ownerName.equals(player.getDisplayName())) {
            return true;
        }

        //if a ticket is needed, like for passenger cars
        if(getBoolean(boolValues.LOCKED) && getRiderOffsets().length>1){
            for(ItemStack stack : player.inventory.mainInventory){
                if(stack.getItem() instanceof ItemKey){
                    for(UUID id : ItemKey.getHostList(stack)){
                        if (id == this.entityUniqueID){
                            if(stack.getItem() instanceof ItemTicket &&decreaseTicketStack) {
                                stack.stackSize--;
                                if (stack.stackSize<=0){
                                    stack=null;
                                }
                            }
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        //all else fails, just return if this is locked.
        return !getBoolean(boolValues.LOCKED);

    }

    @Override
    protected void setRotation(float p_70101_1_, float p_70101_2_) {
        this.prevRotationYaw = this.rotationYaw = p_70101_1_;
        this. prevRotationPitch = this.rotationPitch = p_70101_2_;
    }

    protected void setRotation(float yaw, float pitch, float roll){
        setRotation(yaw, pitch);
        this.prevRotationRoll = this.rotationRoll = roll;
    }


    public GameProfile getOwner(){
        if (ownerName != null && !ownerName.equals("") && worldObj.getPlayerEntityByName(ownerName) !=null){
            return (worldObj.getPlayerEntityByName(ownerName)).getGameProfile();
        }
        return null;
    }

    /**defines the ID of the owner*/
    public String getOwnerName(){return ownerName.equals("")?this.dataWatcher.getWatchableObjectString(23):ownerName;}

    public skin getTexture(EntityPlayer viewer){
        return getSkinList(viewer, false).get(this.dataWatcher.getWatchableObjectString(24));
    }

    /**
     * NOTE: lists are hash maps, their index order is different every time an entry is added or removed.
     * todo: reliability improvement: make a version of this that builds a list of the keys
     *     and then use the indexes of the keys to iterate, keys could also be cached on init of the skins
     *     or we could move to some form of ordered map, although that would damage normal render performance.
     * @param viewer
     * @param isPaintBucket
     * @param skinId
     * @return
     */
    public skin getTextureByID(EntityPlayer viewer, boolean isPaintBucket, String skinId){
        return getSkinList(viewer, isPaintBucket).get(skinId);
    }

    /**
     * Method to allow entities to override skin interactions.
     * for example, only allowing a specific player to apply a skin from the paint bucket,
     *     or returning a different skin during render based on the transport's state.
     *
     * If the player is null, then the call is being made for saving and loading, and usually should not be modified.
     * When the player is null, isPaintBucket is false, so that allows null checks to be skipped by checking the bool first, in most cases.
     */
    public Map<String, skin> getSkinList(EntityPlayer viewer, boolean isPaintBucket){
        return SkinRegistry.getTransportSkins(getClass());
    }

    /**
     * return the name for the default skin of the transport.
     */
    public String getDefaultSkin(){
        return getSkinList(null,false)==null?"":getSkinList(null, false).keySet().iterator().next();}

    public List<ParticleFX> getParticles(){
        return renderData.particles;
    }


    @SideOnly(Side.CLIENT)
    public boolean isInRangeToRenderDist(double p_70112_1_)
    {
        return p_70112_1_ > 1D;
    }


    /*
     * <h1>Inventory management</h1>
     */

    /**
     * <h2>inventory size</h2>
     * @return the number of slots the inventory should have.
     * if it's a train we have to calculate the size based on the type and the size of inventory its supposed to have.
     * trains get 1 extra slot by default for fuel, steam and nuclear steam get another slot, and if it can take passengers there's another slot, this is added to the base inventory size.
     * if it's not a train or rollingstock, then just return the base amount for a crafting table.
     */
    @Override
    public int getSizeInventory() {
        return inventory.size();
    }

    /**
     * <h2>get item</h2>
     * @return the item in the requested slot
     */
    @Override
    public ItemStack getStackInSlot(int slot) {
        if (inventory == null || slot <0 || slot >= inventory.size()){
            return null;
        } else {
            return inventory.get(slot).getStack();
        }
    }

    public ItemStackSlot getSlotIndexByID(int id){
        for(ItemStackSlot s : inventory){
            if (s.getSlotID() == id){
                return s;
            }
        }
        return null;
    }

    /**
     * <h2>decrease stack size</h2>
     * @return the itemstack with the decreased size. If the decreased size is equal to or less than the current stack size it returns null.
     */
    @Override
    public ItemStack decrStackSize(int slot, int stackSize) {
        if (inventory!= null && getSizeInventory()>=slot) {
            return inventory.get(slot).decrStackSize(stackSize);
        } else {
            return null;
        }
    }

    /**
     * <h2>Set slot</h2>
     * sets the slot contents, this is a direct override so we don't have to compensate for anything.
     */
    @Override
    public void setInventorySlotContents(int slot, ItemStack itemStack) {
        if (inventory != null && slot >=0 && slot <= getSizeInventory()) {
            inventory.get(slot).setSlotContents(itemStack,inventory);
        }
    }

    /**
     * <h2>name and stack limit</h2>
     * These are grouped together because they are pretty self-explanatory.
     */
    @Override
    public String getInventoryName() {return transportName() + ".storage";}
    @Override
    public boolean hasCustomInventoryName() {return inventory != null;}
    @Override
    public int getInventoryStackLimit() {return inventory!=null?64:0;}
    @Override
    public ItemStack getCartItem(){
        return new ItemStack(getItem(),1);
    }


    /**returns the type of transport, this is planned to be removed in favor of a few more methods.
     * for a list of options:
     * @see TrainsInMotion.transportTypes
     * may not return null.*/
    public List<TrainsInMotion.transportTypes> getTypes(){return TrainsInMotion.transportTypes.SLUG.singleton();}

    /**
     * <h2>is Locked</h2>
     * returns if the entity is locked, and if it is, if the player is the owner.
     * This makes sure the inventory can be accessed by anyone if its unlocked and only by the owner when it is locked.
     * if it's a tile entity, it's just another null check to be sure no one crashes.
     */
    @Override
    public boolean isUseableByPlayer(EntityPlayer p_70300_1_) {return getPermissions(p_70300_1_, false, false);}

    /**
     * <h2>filter slots</h2>
     * used to filter inventory slots for specific items or data.
     * @param slot the slot that yis being interacted with
     * @param itemStack the stack that's being added
     * @return whether or not it can be added
     */
    @Override
    public boolean isItemValidForSlot(int slot, ItemStack itemStack) {
        if (itemStack == null){return true;}
        //compensate for specific rollingstock
        for(TrainsInMotion.transportTypes type : getTypes()){
            if(type==LOGCAR && (RailUtility.isLog(itemStack) || RailUtility.isPlank(itemStack))){
                return true;
            }
            if (type==COALHOPPER && RailUtility.isCoal(itemStack)){
                return true;
            }
            if (type==STEAM){
                if (slot == 36) {
                    return TileEntityFurnace.getItemBurnTime(itemStack) > 0;
                } else if (slot ==37) {
                    return FuelHandler.getUseableFluid(itemStack, this) != null;
                }
            }
            if (type==ELECTRIC && slot==36){
                return FuelHandler.getUseableFluid(itemStack, this) != null;
            }
        }
        return true;
    }

    /**
     * <h2>Add item to train inventory</h2>
     * custom function for adding items to the train's inventory.
     * similar to a container's TransferStackInSlot function, this will automatically sort an item into the inventory.
     * if there is no room in the inventory for the item, it will drop on the ground.
     */
    public void addItem(ItemStack item){
        for(ItemStackSlot slot : inventory){
            item = slot.mergeStack(item,inventory);
            if (item == null){
                return;
            }
        }
        entityDropItem(item, item.stackSize);
    }

    /**
     * <h2>inventory percentage count</h2>
     * calculates percentage of inventory used then returns a value based on the intervals.
     * for example if the inventory is half full and the intervals are 100, it returns 50. or if the intervals were 90 it would return 45.
     */
    public int calculatePercentageOfSlotsUsed(int indexes){
        if (inventory == null){
            return 0;
        }
        float i=0;
        for (ItemStackSlot item : inventory){
            if (item.getHasStack()){
                i++;
            }
        }
        return i>0?MathHelper.floor_double(((i / getSizeInventory()) *indexes)+0.5):0;
    }


    /**
     * <h2>get an item from inventory to render</h2>
     * cycles through the items in the inventory and returns the first non-null item that's index is greater than the provided number.
     * if it fails to find one it subtracts one from the index and tries again, and keeps trying until the index is negative, in which case it returns 0.
     */
    public ItemStack getFirstBlock(int index){
        for (int i=0; i<getSizeInventory(); i++){
            if (i>= index && inventory.get(i) != null && inventory.get(i).getHasStack() && inventory.get(i).getItem() instanceof ItemBlock){
                return inventory.get(i).getStack();
            }
        }
        return getFirstBlock(index>0?index-1:0);
    }

    /*
     * <h2>unused</h2>
     * we have to initialize these values, but due to the design of the entity we don't actually use them.
     */
    /**used to sync the inventory on close.*/
    @Override
    public ItemStack getStackInSlotOnClosing(int p_70304_1_) {
        return inventory==null || inventory.size()<p_70304_1_?null:inventory.get(p_70304_1_).getStack();
    }
    @Override
    public void markDirty() {forceBackupTimer = 30;}
    /**called when the inventory GUI is opened*/
    @Override
    public void openInventory() {}
    /**called when the inventory GUI is closed*/
    @Override
    public void closeInventory() {
        if (!worldObj.isRemote){
            ServerLogger.writeWagonToFolder(this);
        }
    }

    public void dropAllItems() {
        if (inventory != null) {
            for (ItemStackSlot slot : inventory) {
                if (slot.getStack() != null) {
                    this.entityDropItem(slot.getStack(), 1);
                    slot.setSlotContents(null,null);
                }
            }
        }
    }


    /*
     * <h1>Fluid Management</h1>
     */
    //attempt to drain a set amount
    //todo maybe this should cover all tanks...?
    @Override
    public FluidStack drain(@Nullable ForgeDirection from, int drain, boolean doDrain){
        if (getTankInfo(null)[0].fluid.amount<1){
            return null;
        } else {
            int amountToDrain = getTankInfo(null)[0].fluid.amount < drain?getTankInfo(null)[0].fluid.amount:drain;
            if (doDrain){
                if (amountToDrain == getTankInfo(null)[0].fluid.amount) {
                    getTankInfo(null)[0] = null;
                    updateWatchers=true;
                } else {
                    getTankInfo(null)[0].fluid.amount -= amountToDrain;
                    updateWatchers=true;
                }
            }
            return getTankInfo(null)[0]!=null?new FluidStack(getTankInfo(null)[0].fluid.getFluid(), amountToDrain):null;
        }
    }


    @Override
    public boolean canPassFluidRequests(Fluid fluid){
        return canDrain(null, fluid) || canFill(null, fluid);
    }

    @Override
    public boolean canAcceptPushedFluid(EntityMinecart requester, Fluid fluid){
        return canFill(null, fluid);
    }

    @Override
    public boolean canProvidePulledFluid(EntityMinecart requester, Fluid fluid){
        return canDrain(ForgeDirection.UNKNOWN, fluid);
    }

    @Override
    public void setFilling(boolean filling){}

    /**Returns true if the given fluid can be extracted.*/
    @Override
    public boolean canDrain(@Nullable ForgeDirection from, Fluid resource){
        for(FluidTankInfo stack : getTankInfo(null)) {
            if (stack.fluid.amount > 0 && (resource == null || stack.fluid.getFluid() == resource)) {
                return true;
            }
        }
        return false;
    }
    /**Returns true if the given fluid can be inserted into the fluid tank.*/
    @Override
    //TODO: rework this to work more similar to the fill function
    public boolean canFill(@Nullable ForgeDirection from, Fluid resource){
        return true;
    }

    /**drain with a fluidStack, this is mostly a redirect to
     * @see #drain(ForgeDirection, int, boolean) but with added filtering for fluid type.
     */
    @Override
    public FluidStack drain(@Nullable ForgeDirection from, FluidStack resource, boolean doDrain){
        int leftoverDrain=resource.amount;
        for(FluidTankInfo stack : getTankInfo(null)) {
            if (stack.fluid.amount > 0 && stack.fluid.getFluid() == resource.getFluid()) {
                if(leftoverDrain>stack.fluid.amount){
                    leftoverDrain-=stack.fluid.amount;
                    if(doDrain){
                        stack.fluid.amount=0;
                    }
                } else {
                    if(doDrain){
                        stack.fluid.amount-=leftoverDrain;
                    }
                    return null;
                }
            }
        }
        return resource;

    }

    /**checks if the fluid can be put into the tank, and if doFill is true, will actually attempt to add the fluid to the tank.
     * @return the amount of fluid that was or could be put into the tank.*/
    @Override
    public int fill(@Nullable ForgeDirection from, FluidStack resource, boolean doFill){
        if(getTankCapacity()==null){return resource.amount;}
        int leftoverDrain=resource.amount;
        for(int stack =0; stack<getTankCapacity().length;stack++) {
            if(getTankFilters()!=null && getTankFilters()[stack]!=null) {
                boolean check=false;
                for (String filter : getTankFilters()[stack]) {
                    if (filter.length()==0 || RailUtility.stringContains(filter,resource.getFluid().getName())){
                        check=false;
                        break;
                    } else {
                        check=true;
                    }
                }
                if(check){
                    continue;
                }
            }
            if (getTankInfo(null)[stack]!=null && (
                    resource.getFluid() == null || getTankInfo(null)[stack].fluid.getFluid() == resource.getFluid() ||
                            getTankInfo(null)[stack].fluid.amount ==0)) {

                if(leftoverDrain+getTankInfo(null)[stack].fluid.amount>getTankInfo(null)[stack].capacity){
                    leftoverDrain-=getTankInfo(null)[stack].capacity-getTankInfo(null)[stack].fluid.amount;
                    if(doFill){
                        getTankInfo(null)[stack] = new FluidTankInfo(
                                new FluidStack(resource.fluid, getTankInfo(null)[stack].capacity), getTankInfo(null)[stack].capacity);
                    }
                } else if (leftoverDrain+getTankInfo(null)[stack].fluid.amount<0){
                    leftoverDrain-=getTankInfo(null)[stack].fluid.amount-resource.amount;
                    if(doFill){
                        getTankInfo(null)[stack] = new FluidTankInfo(
                                new FluidStack(getTankInfo(null)[stack].fluid, 0), getTankInfo(null)[stack].capacity);
                    }
                } else {
                    if(doFill){
                        getTankInfo(null)[stack] = new FluidTankInfo(
                                new FluidStack(resource.fluid, getTankInfo(null)[stack].fluid.amount+leftoverDrain),
                                getTankInfo(null)[stack].capacity);
                    }
                    leftoverDrain=0;
                }
                if(leftoverDrain==0){
                    return 0;
                }
            }
        }
        return leftoverDrain;
    }

    /**
     * forced fill method, attempts to fill containers with the entire amount.
     * this is mainly used by the fuel handler as a shorthand but can be manually referenced by other things
     * @param from the direction to fill from, normally doesn't make a difference, can be null
     * @param resource the fluid to fill with
     * @return true if the tank was able to fill with the entire stack, false if not.
     */
    public boolean fill(@Nullable ForgeDirection from, FluidStack resource){
        if(getTankCapacity()==null || resource==null ||resource.amount<1){return false;}
        for(int stack =0; stack<getTankCapacity().length;stack++) {
            if(getTankFilters()!=null && getTankFilters()[stack]!=null) {
                boolean check=false;
                for (String filter : getTankFilters()[stack]) {
                    if (filter.length()==0 || RailUtility.stringContains(filter,resource.getFluid().getName())){
                        check=false;
                        break;
                    } else {
                        check=true;
                    }
                }
                if(check){
                    continue;
                }
            }
            if (getTankInfo(null)[stack]!=null && (
                    resource.getFluid() == null || getTankInfo(null)[stack].fluid.getFluid() == resource.getFluid() ||
                            getTankInfo(null)[stack].fluid.amount ==0)) {
                if(resource.amount+getTankInfo(null)[stack].fluid.amount<=getTankInfo(null)[stack].capacity){
                    getTankInfo(null)[stack] = new FluidTankInfo(
                            new FluidStack(resource.fluid, getTankInfo(null)[stack].fluid.amount+resource.amount),
                            getTankInfo(null)[stack].capacity);
                    return true;
                }
            }
        }
        return false;
    }

    /**returns the list of fluid tanks and their capacity.*/
    @Override
    public FluidTankInfo[] getTankInfo(ForgeDirection from){
        if(getTankCapacity()==null || getTankCapacity().length ==0){
            return new FluidTankInfo[]{};
        }

        if (fluidTank==null || fluidTank.length<getTankCapacity().length) {
            //initialize tanks
            FluidTankInfo[] tanks = new FluidTankInfo[getTankCapacity().length];
            for (int i = 0; i < getTankCapacity().length; i++) {
                tanks[i] = new FluidTankInfo(new FluidStack(FluidRegistry.WATER, 0), getTankCapacity()[i]);
            }
            fluidTank = tanks;
        }
        //if its server, remake when null, otherwise remake if changed, this is called every frame when a GUI is up and/or if the model needs to render it so cache VERY important.
        if(worldObj!=null && worldObj.isRemote && fluidCache.equals("") || !fluidCache.equals(dataWatcher.getWatchableObjectString(20))){

            //actually put in the data
            fluidCache = dataWatcher.getWatchableObjectString(20);
            if (fluidCache.length()>3) {
                String[] fluids = fluidCache.split(";");
                for (int i = 0; i < getTankCapacity().length; i++) {
                    String[] data = fluids[i].split(",");
                    fluidTank[i] = new FluidTankInfo(new FluidStack(FluidRegistry.getFluid(data[1]), Integer.parseInt(data[0])), getTankCapacity()[i]);
                }
            }
        }

        return fluidTank;
    }

    /*
     * <h3> chunk management</h3>
     * small chunk management for the entity, most all of it is handled in
     * @see ChunkHandler
     */

    /**@return the chunk ticket of this entity*/
    public ForgeChunkManager.Ticket getChunkTicket(){return chunkTicket;}
    /**sets the chunk ticket of this entity to the one provided.*/
    public void setChunkTicket(ForgeChunkManager.Ticket ticket){chunkTicket = ticket;}

    /**attempts to get a ticket for chunkloading, sets the ticket's values*/
    private void requestTicket() {
        ForgeChunkManager.Ticket ticket = ForgeChunkManager.requestTicket(TrainsInMotion.instance, worldObj , ForgeChunkManager.Type.ENTITY);
        if(ticket != null) {
            ticket.bindEntity(this);
            setChunkTicket(ticket);
        }
    }

    /*
     * <h2>Inherited variables</h2>
     * these functions are overridden by classes that extend GenericRailTransport, or EntityTrainCore so that way the values can be changed indirectly.
     */

    /*
    <h1>Bogies and models</h1>
    */

    /**returns the x/y/z offset each bogie should render at, with 0 being the entity center, in order with getBogieModels
     * example:
     * return new float[][]{{x1,y1,z1},{x2,y2,z2}, etc...};
     * may return null.*/
    @Deprecated
    @SideOnly(Side.CLIENT)
    public float[][] bogieModelOffsets(){return null;}

    /**returns a list of models to be used for the bogies
     * example:
     * return new ModelBase[]{new MyModel1(), new myModel2(), etc...};
     * may return null. */
    @Deprecated
    @SideOnly(Side.CLIENT)
    public ModelBase[] bogieModels(){return null;}


    @SideOnly(Side.CLIENT)
    public Bogie[] bogies(){
        if(bogieModelOffsets()==null || bogieModels()==null){return null;}
        Bogie[] ret = new Bogie[bogieModelOffsets().length];
        for(int i=0; i<bogieModelOffsets().length;i++){
            if(i>=bogieModels().length){
                ret[i] = new Bogie(bogieModels()[0], bogieModelOffsets()[i]);
            } else {
                ret[i] = new Bogie(bogieModels()[i], bogieModelOffsets()[i]);
            }
        }
        return ret;
    }

    /**defines the points that the entity uses for path-finding and rotation, with 0 being the entity center.
     * Usually the point where the front and back bogies would connect to the transport.
     * Or the center of the frontmost and backmost wheel if there are no bogies.
     * example:
     * return new float{2f, -1f};
     * may not return null*/
    public float[] bogieLengthFromCenter(){return new float[]{1,-1};}

    /**defines the radius from center in microblocks that the pistons animate, if there are any.*/
    public float getPistonOffset(){return 0;}

    /**defines the scale to render the model at. Default is 0.0625*/
    public float getRenderScale(){return 0.0625f;}

    /**defines the scale to render the model at. Default is 0.65*/
    public float getPlayerScale(){return 0.65f;}

    /**returns the x/y/z offset each model should render at, with 0 being the entity center, in order with getModels
     * example:
     * return new float[][]{{x1,y1,z1},{x2,y2,z2}, etc...};
     * may return null.*/
    @SideOnly(Side.CLIENT)
    public float[][] modelOffsets(){return null;}

    /**event is to add skins for the model to the skins registry on mod initialization.
     * this function can be used to register multiple skins, one after another.
     * example:
     * SkinRegistry.addSkin(this.class, MODID, "folder/mySkin.png", new int[][]{{oldHex, newHex},{oldHex, newHex}, etc... }, displayName, displayDescription);
     * the int[][] for hex recolors may be null.
     * hex values use "0x" in place of "#"
     * "0xff00aa" as an example.
     * the first skin added to the registry for a transport class will be the default
     * additionally the addSkin function may be called from any other class at any time.
     * the registerSkins method is only for organization and convenience.*/
    public void registerSkins(){}

    /**returns a list of models to be used for the transport
     * example:
     * return new MyModel();
     * may return null. */
    @SideOnly(Side.CLIENT)
    public ModelBase[] getModel(){return null;}


    /*
    <h1>riders and interaction</h1>
    */

    /**defines the rider position offsets, with 0 being the center of the entity.
     * Each set of coords represents a new rider seat, with the first one being the "driver"
     * example:
     * return new float[][]{{x1,y1,z1},{x2,y2,z2}, etc...};
     * may return null*/
    public float[][] getRiderOffsets(){return null;}

    /**returns the size of the hitbox in blocks.
     * example:
     * return new float[]{x,y,z};
     * may not return null*/
    public float[] getHitboxSize(){return new float[]{3,1.5f,.21f};}

    /**defines if the transport is immune to explosions*/
    public boolean isReinforced(){return false;}


    /*
    <h1> inventory and fluid tanks </h1>
    */

    /**defines the size of the inventory row by row, not counting any special slots like for fuel.
     * end result number of slots is this times 9. plus any crafting/fuel slots
     * may not return null*/
    public int getInventoryRows(){return 0;}

    /**defines the capacity of the fluidTank tank.
     * each value defibes another tank.
     * Usually value is 1,000 *the cubic meter capacity, so 242 gallons, is 0.9161 cubic meters, which is 916.1 tank capacity
     * mind you one water bucket is values at 1000, a full cubic meter of water.
     *example:
     * return new int[]{11000, 1000};
     * may return null*/
    public int[] getTankCapacity(){return null;}

    /** defines the whitelist of fluid names for fluid tanks in order.
     * null will accept any fluid.
     * example:
     * return new String[][]{{"water", "diesel"}, {"lava"}, null}*/
    public String[][] getTankFilters(){return null;}


    /**this function allows individual trains and rollingstock to implement custom fuel consumption and management
     * you can call one of the existing methods in the FuelHandler class:
     * manageSteam, manageElectric, manageDiesel
     * you may also leave it empty if you don't plan to use it.
     * for more detail on implementing custom versions, take a look at the existing ones, for example:
     * @see FuelHandler#manageSteam(EntityTrainCore) for an example*/
    public void manageFuel(){}

    /** returns the max fuel.
     * for steam trains this is cubic meters of the firebox size. (1.5 on average)
     * for diesel this is cubic meters of the fuel tank. (11.3 on average)
     * for electric this is Kw. (400 on average)*/
    public float getMaxFuel(){return 0;}


    /**
     * returns an array of integers.
     * the first is density.
     * the second is scale in percentage.
     * the third is color in hex.
     * todo: the fourth is speed in percentage (does not apply to cone or sphere lamps)
     * @param id the index of the particle defined in the model
     */
    @SideOnly(Side.CLIENT)
    public int[] getParticleData(int id){
        switch (id){
            case 0:{return new int[]{3, 100, 0x232323};}//smoke
            case 1:{return new int[]{5, 100, 0x232323};}//heavy smoke
            case 2:{return new int[]{2, 100, 0xEEEEEE};}//steam
            case 3:{return new int[]{6, 100, 0xCECDCB};}//led lamp
            case 4:{return new int[]{3, 50, 0xCC0000};}//reverse lamp
            case 5:{return new int[]{3, 10, 0xCCCC00};}//small sphere lamp
            default:{return new int[]{5, 100, 0xCCCC00};}//lamp
        }
    }


    /**defines the weight of the transport.*/
    public float weightKg(){return 907.18474f;}

    /**defines the recipe in order from topleft to bottom right.
     * example:
     * return new ItemStack[]{new ItemStack(Blocks.dirt, 2), new ItemStack(Blocks.glass,1), etc};
     * array must contain 9 values. may not return null.*/
    public ItemStack[] getRecipie(){return new ItemStack[]{
            new ItemStack(Blocks.dirt),null,null,null,null,null,null,null,null
    };}


    /**defines the name used for registration and the default name used in the gui.*/
    public String transportName(){return "Fugidsnot";}
    /**defines the country of origin for the transport*/
    public String transportcountry(){return "Nowhere";}
    /**the year or year range to display for the transport.*/
    public String transportYear(){return "19 somethin'";}

    /**the fuel type to display for the transport.*/
    public String transportFuelType(){return "Magic";}

    /**the top speed in km/h for the transport.
     * not used tor rollingstock.*/
    public float transportTopSpeed(){return 0;}
    /**displays in item lore if the transport is fictional or not*/
    public boolean isFictional(){return true;}
    /**the tractive effort for the transport, this is a fallback if metric horsepower (mhp) is not available*/
    public float transportTractiveEffort(){return 0;}
    /**this is the default value to define the acceleration speed and pulling power of a transport.*/
    public float transportMetricHorsePower(){return 0;}

    /**additional lore for the item, each entry in the array is a new line.
     * return null if unused.*/
    public String[] additionalItemText(){return null;}

    /**returns the item of this transport, this should be a static value in the transport's class.
     * example:
     * public static final Item thisItem = new ItemTransport(new EntityThis(null));
     * Item getItem(){return thisItem;}
     * may not return null*/
    public Item getItem(){return null;}

}