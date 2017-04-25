package ebf.tim.entities;

import com.mojang.authlib.GameProfile;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.registry.IEntityAdditionalSpawnData;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import ebf.tim.TrainsInMotion;
import ebf.tim.items.ItemKey;
import ebf.tim.models.tmt.Vec3d;
import ebf.tim.networking.PacketRemove;
import ebf.tim.registry.NBTKeys;
import ebf.tim.utility.*;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.Entity;
import net.minecraft.entity.IEntityMultiPart;
import net.minecraft.entity.boss.EntityDragonPart;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.DamageSource;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.*;
import net.minecraftforge.oredict.OreDictionary;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static ebf.tim.TrainsInMotion.proxy;
import static ebf.tim.utility.RailUtility.rotatePoint;

/**
 * <h1>Generic Rail Transport</h1>
 * this is the base for all trains and rollingstock.
 * @author Eternal Blue Flame
 */
public class GenericRailTransport extends Entity implements IEntityAdditionalSpawnData, IEntityMultiPart, IInventory, IFluidHandler {


    /**
     * <h3>Ore directory support</h3>
     * creates lists of the items by ore directory names to add support for 3rd party mods.
     */
    /**collects all the wood planks, slabs, and logs in the ore directory*/
    private static final ArrayList<ItemStack> logCarrier = combineStacks(combineStacks(OreDictionary.getOres("plankWood"), OreDictionary.getOres("slabWood")), OreDictionary.getOres("logWood"));
    /**collects all the coal in in the ore directory*/
    private static final ArrayList<ItemStack> coalCarrier = OreDictionary.getOres("coal");

    /**
     * <h2>variables</h2>
     */
    /**if the owner has locked this.*/
    public boolean isLocked = false;
    /**if the handbrake is on.*/
    public boolean brake = false;
    /**defines the lamp, and it's management*/
    public LampHandler lamp = new LampHandler();
    /**defines the colors, the outer array is for each different color, and the inner int[] is for the RGB color*/
    public int[][] colors = new int[][]{{0,0,0},{0,0,0},{0,0,0}};
    /**the server-sided persistent UUID of the owner*/
    private UUID owner = null;
    /**the front entity bogie*/
    public EntityBogie frontBogie = null;
    /**the back entity bogie*/
    public EntityBogie backBogie = null;
    /**the list of seat entities*/
    public List<EntitySeat> seats = new ArrayList<EntitySeat>();
    /**whether or not this should consume fuel and be able to derail.*/
    public boolean isCreative = false;
    /**whether or not to check for cars to link to*/
    public boolean isCoupling = false;
    /**the list of hitboxes and the class that manages them*/
    public HitboxHandler hitboxHandler = new HitboxHandler();
    /**the server-sided persistent UUID of the transport linked to the front of this,*/
    public UUID frontLinkedTransport = null;
    /**the server-sided persistent UUID of the transport linked to the back of this,*/
    public UUID backLinkedTransport = null;
    /**the ID of the owner*/
    public int ownerID =0;
    /**the destination for routing*/
    public String destination ="";
    /**the key item for the entity*/
    public ItemStack key;
    /**used to initialize a laege number of variables that are used to calculate everything from movement to linking.
     * this is so we don't have to initialize each of these variables every tick, saves CPU.*/
    private double[][] vectorCache = new double[7][3];
    /**the health of the entity, similar to that of EntityLiving*/
    private int health = 20;
    /**the fluidTank tank*/
    private FluidStack fluidTank = null;
    /**the list of items used for the inventory and crafting slots.*/
    private List<ItemStack> items;
    /**the lst of unlocalized names for the filter*/
    private List<String> filter = new ArrayList<String>();
    /**whether the filter should only take the items in the list (whitelist), or whether it should take anything but the items in the list (blacklist).*/
    private boolean isWhitelist = false;
    /**whether or not this needs to update the datawatchers*/
    private boolean updateWatchers = false;

    public GenericRailTransport(World world){
        super(world);
    }
    public GenericRailTransport(UUID owner, World world, double xPos, double yPos, double zPos){
        super(world);

        posY = yPos;
        posX = xPos;
        posZ = zPos;
        this.owner = owner;
        key = new ItemStack(new ItemKey(owner, getItem().getUnlocalizedName()));
    }

    /**
     * <h2>Entity initialization</h2>
     * Entity init runs right before the first tick.
     * This is useful for registering the datawatchers and inventory before we actually use them.
     */
    @Override
    public void entityInit(){
        this.dataWatcher.addObject(19, 0);//owner
        this.dataWatcher.addObject(20, 0);//tankA
        if (getInventorySize() != TrainsInMotion.inventorySizes.NULL && items == null) {
            items = new ArrayList<ItemStack>();
            while (items.size() < getSizeInventory()) {
                items.add(null);
            }
            if (getRiderOffsets() != null && getRiderOffsets().length > 1) {
                items.add(null);
            }
        }
    }


    /**
     * <h2>base entity overrides</h2>
     * modify basic entity variables to give different uses/values.
     */
     /**returns if the player can push this, we actually use our own systems for this, so we return false*/
    @Override
    public boolean canBePushed() {return false;}
    /**returns the world object for IEntityMultipart*/
    @Override
    public World func_82194_d(){return worldObj;}
    /**returns the hitboxes of the entity as an array rather than a list*/
    @Override
    public Entity[] getParts(){return hitboxHandler.hitboxList.toArray(new HitboxHandler.MultipartHitbox[hitboxHandler.hitboxList.size()]);}
    /**returns the hitbox of this entity, we dont need that so return null*/
    @Override
    public AxisAlignedBB getBoundingBox(){return null;}
    /**returns the hitbox of this entity, we dont need that so return null*/
    @Override
    public AxisAlignedBB getCollisionBox(Entity collidedWith){return null;}
    /**returns if this can be collided with, we don't use this so return false*/
    @Override
    public boolean canBeCollidedWith() {return false;}
    /**client only positioning of the transport, this should help to smooth the movement*/
    @SideOnly(Side.CLIENT)
    public void setPositionAndRotation2(double p_70056_1_, double p_70056_3_, double p_70056_5_, float p_70056_7_, float p_70056_8_, int p_70056_9_) {
        if (frontBogie!=null && backBogie!= null){
            setPosition(
                    (frontBogie.posX + backBogie.posX) * 0.5D,
                    ((frontBogie.posY + backBogie.posY) * 0.5D),
                    (frontBogie.posZ + backBogie.posZ) * 0.5D);

            setRotation((float)Math.toDegrees(Math.atan2(
                    frontBogie.posZ - backBogie.posZ,
                    frontBogie.posX - backBogie.posX)),
                    MathHelper.floor_double(Math.acos(frontBogie.posY / backBogie.posY)*RailUtility.degreesD));
        }else {
            this.setPosition(p_70056_1_, p_70056_3_, p_70056_5_);
        }
        this.setRotation(p_70056_7_, p_70056_8_);
    }


    /**
     * <h2>damage and destruction</h2>
     * attackEntityFromPart is called when one of the hitboxes of the entity has taken damage of some form.
     * the damage done is handled manually so we can compensate for basically everything, and if health is 0 or lower, we destroy the entity part by part, leaving the main part of the entity for last.
     */
    @Override
    public boolean attackEntityFromPart(EntityDragonPart part, DamageSource damageSource, float damage){
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
            //remove bogies
            frontBogie.isDead = true;
            TrainsInMotion.keyChannel.sendToServer(new PacketRemove(frontBogie.getEntityId()));
            worldObj.removeEntity(frontBogie);
            backBogie.isDead = true;
            TrainsInMotion.keyChannel.sendToServer(new PacketRemove(backBogie.getEntityId()));
            worldObj.removeEntity(backBogie);
            //remove seats
            for (EntitySeat seat : seats){
                seat.isDead = true;
                TrainsInMotion.keyChannel.sendToServer(new PacketRemove(seat.getEntityId()));
                seat.worldObj.removeEntity(seat);
            }
            //remove hitboxes
            for (EntityDragonPart hitbox : hitboxHandler.hitboxList){
                hitbox.isDead = true;
                TrainsInMotion.keyChannel.sendToServer(new PacketRemove(hitbox.getEntityId()));
                hitbox.worldObj.removeEntity(hitbox);
            }
            //if the damage source is an explosion, or this (from overheating as an example), we circumstantially blow up.
            //radius is defined by whether or not it's a nuclear train, and fire spread creation is defined by whether or not it's diesel.
            if (damageSource.getSourceOfDamage() == this || damageSource.isExplosion()){
                worldObj.newExplosion(this, posX, posY, posZ,
                        (this.getType() == TrainsInMotion.transportTypes.NUCLEAR_STEAM || this.getType() == TrainsInMotion.transportTypes.NUCLEAR_ELECTRIC)? 6:12,
                        this.getType() == TrainsInMotion.transportTypes.DIESEL, true);
                //if this isn't supposed to explode, and the damage source wasn't an explosion, drop the item for this transport
            }
            //remove this
            isDead=true;
            TrainsInMotion.keyChannel.sendToServer(new PacketRemove(getEntityId()));
            worldObj.removeEntity(this);
        }
        return false;
    }


    /**
     * <h3>add bogies and seats</h3>
     */

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
    /** this is called by the seats and seats on their spawn to add them to this entity's list of seats, we only do it on client because that's the only side that seems to lose track.
     * @see EntitySeat#readSpawnData(ByteBuf)*/
    @SideOnly(Side.CLIENT)
    public void addseats(EntitySeat cart){seats.add(cart);}

    /**
     * <h2> Data Syncing and Saving </h2>
     * SpawnData is mainly used for data that has to be created on client then sent to the server, like data processed on item use.
     * NBT is save data, which only happens on server.
     */

    /**reads the data sent from client on entity spawn*/
    @Override
    public void readSpawnData(ByteBuf additionalData) {
        brake = additionalData.readBoolean();
        isCoupling = additionalData.readBoolean();
        isCreative = additionalData.readBoolean();
        lamp.isOn = additionalData.readBoolean();
        owner = new UUID(additionalData.readLong(), additionalData.readLong());
        key = new ItemStack(new ItemKey(owner, getItem().getUnlocalizedName()));
        rotationYaw = additionalData.readFloat();
    }
    /**sends the data to server from client*/
    @Override
    public void writeSpawnData(ByteBuf buffer) {
        buffer.writeBoolean(brake);
        buffer.writeBoolean(isCoupling);
        buffer.writeBoolean(isCreative);
        buffer.writeBoolean(lamp.isOn);
        buffer.writeLong(owner.getMostSignificantBits());
        buffer.writeLong(owner.getLeastSignificantBits());
        buffer.writeFloat(rotationYaw);
    }
    /**loads the entity's save file*/
    @Override
    protected void readEntityFromNBT(NBTTagCompound tag) {
        isLocked = tag.getBoolean(NBTKeys.locked);
        lamp.isOn = tag.getBoolean(NBTKeys.lamp);
        isDead = tag.getBoolean(NBTKeys.dead);
        isCoupling = tag.getBoolean(NBTKeys.coupling);
        //load links
        if (tag.hasKey(NBTKeys.frontLinkMost)) {
            frontLinkedTransport = new UUID(tag.getLong(NBTKeys.frontLinkMost), tag.getLong(NBTKeys.frontLinkLeast));
        }
        if (tag.hasKey(NBTKeys.backLinkMost)) {
            backLinkedTransport = new UUID(tag.getLong(NBTKeys.backLinkMost), tag.getLong(NBTKeys.backLinkLeast));
        }
        //more bools
        isCreative = tag.getBoolean(NBTKeys.creative);
        brake = tag.getBoolean(NBTKeys.handbrake);
        //load owner
        owner = new UUID(tag.getLong(NBTKeys.ownerMost),tag.getLong(NBTKeys.ownerLeast));
        //key.readFromNBT(tag);
        //load tanks
        if (getTankCapacity() >0) {
            fluidTank = FluidStack.loadFluidStackFromNBT(tag);
            if (fluidTank ==new FluidStack(FluidRegistry.WATER,0)){
                fluidTank = null;
            }
        }

        if (getInventorySize() != TrainsInMotion.inventorySizes.NULL) {
            for (int i = 0; i < getSizeInventory(); i++) {
                NBTTagCompound tagCompound = tag.getCompoundTag(NBTKeys.inventoryItem +i);
                if (tagCompound != null){
                    setInventorySlotContents(i, ItemStack.loadItemStackFromNBT(tagCompound));
                }
            }
            isWhitelist = tag.getBoolean(NBTKeys.whitelist);

            int length = tag.getInteger(NBTKeys.filterLength);
            if (length > 0) {
                for (int i = 0; i < length; i++) {
                    filter.add(tag.getString(NBTKeys.filterItem + i));
                }
            }
        }

        updateWatchers = true;

    }
    /**saves the entity to server world*/
    @Override
    protected void writeEntityToNBT(NBTTagCompound tag) {
        tag.setBoolean(NBTKeys.locked, isLocked);
        tag.setBoolean(NBTKeys.lamp, lamp.isOn);
        tag.setBoolean(NBTKeys.dead, isDead);
        tag.setBoolean(NBTKeys.coupling, isCoupling);
        //frontLinkedTransport and backLinkedTransport bogies
        if (frontLinkedTransport != null){
            tag.setLong(NBTKeys.frontLinkMost, frontLinkedTransport.getMostSignificantBits());
            tag.setLong(NBTKeys.frontLinkLeast, frontLinkedTransport.getLeastSignificantBits());
        }
        if (backLinkedTransport != null){
            tag.setLong(NBTKeys.backLinkMost, backLinkedTransport.getMostSignificantBits());
            tag.setLong(NBTKeys.backLinkLeast, backLinkedTransport.getLeastSignificantBits());
        }
        //more bools
        tag.setBoolean(NBTKeys.creative, isCreative);
        tag.setBoolean(NBTKeys.handbrake, brake);
        //owner
        tag.setLong(NBTKeys.ownerMost, owner.getMostSignificantBits());
        tag.setLong(NBTKeys.ownerLeast, owner.getLeastSignificantBits());
        //key.writeToNBT(tag);


        //tanks
        if (getTankCapacity() >0){
            if (fluidTank != null) {
                fluidTank.writeToNBT(tag);
            } else {
                new FluidStack(FluidRegistry.WATER, 0);
            }
        }
        if (getInventorySize() != TrainsInMotion.inventorySizes.NULL && items.size()>0) {
            for (int i =0; i< getSizeInventory(); i++) {
                if (items.get(i) != null) {
                    tag.setTag(NBTKeys.inventoryItem + i, items.get(i).writeToNBT(new NBTTagCompound()));
                }
            }
            tag.setBoolean(NBTKeys.whitelist, isWhitelist);


            tag.setInteger(NBTKeys.filterLength, filter!=null?filter.size():0);
            if (filter != null && filter.size() > 0) {
                for (int i = 0; i < filter.size(); i++) {
                    tag.setString(NBTKeys.filterItem + i, filter.get(i));
                }
            }
        }

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
     * @see #manageLinks()
     * syncing the owner entity ID with client.
     * being sure the transport is listed in the main class (for lighting management).
     * @see ClientProxy#onTick(TickEvent.ClientTickEvent)
     * and updating the lighting block.
     */
    @Override
    public void onUpdate() {
        //if the cart has fallen out of the map, destroy it.
        if (posY < -64.0D & isDead){
            worldObj.removeEntity(this);
        }

        seats.remove(null);
        hitboxHandler.hitboxList.remove(null);

        //be sure bogies exist

        //always be sure the bogies exist on client and server.
        if (!worldObj.isRemote && (frontBogie == null || backBogie == null)) {
            //spawn frontLinkedTransport bogie
            vectorCache[1][0] = getLengthFromCenter();
            vectorCache[0] = RailUtility.rotatePoint(vectorCache[1],rotationPitch, rotationYaw,0);
            frontBogie = new EntityBogie(worldObj, posX + vectorCache[0][0], posY + vectorCache[0][1], posZ + vectorCache[0][2], getEntityId(), true);
            worldObj.spawnEntityInWorld(frontBogie);
            //spawn backLinkedTransport bogie
            vectorCache[1][0] = -getLengthFromCenter();
            vectorCache[0] = RailUtility.rotatePoint(vectorCache[1],rotationPitch, rotationYaw,0);
            backBogie = new EntityBogie(worldObj, posX + vectorCache[0][0], posY + vectorCache[0][1], posZ + vectorCache[0][2], getEntityId(), false);
            worldObj.spawnEntityInWorld(backBogie);


            if (getRiderOffsets() != null && getRiderOffsets().length >1) {
                for (int i = 0; i < getRiderOffsets().length - 1; i++) {
                    EntitySeat seat = new EntitySeat(worldObj, posX, posY, posZ, getEntityId(), i);
                    worldObj.spawnEntityInWorld(seat);
                    seats.add(seat);
                }
            }
        }

        /**
         *check if the bogies exist, because they may not yet, and if they do, check if they are actually moving or colliding.
         * no point in processing movement if they aren't moving or if the train hit something.
         * if it is clear however, then we need to add velocity to the bogies based on the current state of the train's speed and fuel, and reposition the train.
         * but either way we have to position the bogies around the train, just to be sure they don't accidentally fly off at some point.
         *
         */
        if (frontBogie!=null && backBogie != null){
            //handle movement.
            if (!hitboxHandler.getCollision(this)) {
                frontBogie.minecartMove(rotationPitch, rotationYaw, brake);
                backBogie.minecartMove(rotationPitch, rotationYaw, brake);
            } else {
                frontBogie.addVelocity(-frontBogie.motionX,-frontBogie.motionY,-frontBogie.motionZ);
                backBogie.addVelocity(-backBogie.motionX,-backBogie.motionY,-backBogie.motionZ);
                frontBogie.minecartMove(rotationPitch, rotationYaw, brake);
                backBogie.minecartMove(rotationPitch, rotationYaw, brake);
            }

            //position this
            setPosition(
                    ((frontBogie.posX + backBogie.posX) * 0.5D),
                    ((frontBogie.posY + backBogie.posY) * 0.5D),
                    ((frontBogie.posZ + backBogie.posZ) * 0.5D));


            setRotation((float)Math.toDegrees(Math.atan2(
                    frontBogie.posZ - backBogie.posZ,
                    frontBogie.posX - backBogie.posX)),
                    MathHelper.floor_double(Math.acos(frontBogie.posY / backBogie.posY)*RailUtility.degreesD));


            if (ticksExisted %2 ==0) {
                //align bogies
                vectorCache[1][0]= getLengthFromCenter();
                vectorCache[0] = rotatePoint(vectorCache[1], rotationPitch, rotationYaw, 0.0f);
                frontBogie.setPosition(vectorCache[0][0] + posX, frontBogie.posY, vectorCache[0][2] + posZ);
                vectorCache[1][0]= -getLengthFromCenter();
                vectorCache[0] = rotatePoint(vectorCache[1], rotationPitch, rotationYaw, 0.0f);
                backBogie.setPosition(vectorCache[0][0] + posX, backBogie.posY, vectorCache[0][2] + posZ);
            }
            manageLinks();
        }

        //rider updating isn't called if there's no driver/conductor, so just in case of that, we reposition the seats here too.
        if (riddenByEntity == null && getRiderOffsets() != null) {
            for (int i = 0; i < seats.size(); i++) {
                vectorCache[0] = rotatePoint(getRiderOffsets()[i], rotationPitch, rotationYaw, 0);
                vectorCache[0][0] += posX;
                vectorCache[0][1] += posY;
                vectorCache[0][2] += posZ;
                seats.get(i).setPosition(vectorCache[0][0], vectorCache[0][1], vectorCache[0][2]);
            }
        }

        //be sure the owner entityID is currently loaded, this variable is dynamic so we don't save it to NBT.
        if (!worldObj.isRemote &&ticksExisted %10==0){
            Entity player = proxy.getEntityFromUuid(owner);
            if (player!= null) {
                ownerID = player.getEntityId();
                this.dataWatcher.updateObject(19,ownerID);
            }
            if (updateWatchers && fluidTank != null){
                dataWatcher.updateObject(20, fluidTank.amount);
            }
        }

        /**
         * be sure the client proxy has a reference to this so the lamps can be updated, and then every other tick, attempt to update the lamp position if it's necessary.
         */
        if (backBogie!=null && posX+posY+posZ != 0.0D && !isDead) {
            if (worldObj.isRemote && ClientProxy.EnableLights && !ClientProxy.carts.contains(this)) {
                ClientProxy.carts.add(this);
            }
            if (lamp.Y >1 && worldObj.isRemote && ticksExisted %2 ==1){
                vectorCache[0][0] =this.posX + getLampOffset().xCoord;
                vectorCache[0][1] =this.posY + getLampOffset().yCoord;
                vectorCache[0][2] =this.posZ + getLampOffset().zCoord;
                lamp.ShouldUpdate(worldObj, RailUtility.rotatePoint(vectorCache[0], rotationPitch, rotationYaw, 0));
            }
        }
    }


    /**
     * <h2>Rider offset</h2>
     * this runs every tick to be sure the riders, and seats, are in the correct positions.
     * NOTE: this only happens while there is an entity riding this, entities riding seats do not activate this function.
     */
    @Override
    public void updateRiderPosition() {
        if (getRiderOffsets() != null) {
            if (riddenByEntity != null) {
                vectorCache[2] = rotatePoint(getRiderOffsets()[0], rotationPitch, rotationYaw, 0);
                riddenByEntity.setPosition(vectorCache[2][0] + this.posX, vectorCache[2][1] + this.posY, vectorCache[2][2] + this.posZ);
            }

            for (int i = 0; i < seats.size(); i++) {
                vectorCache[2] = rotatePoint(getRiderOffsets()[i], rotationPitch, rotationYaw, 0);
                vectorCache[2][0] += posX;
                vectorCache[2][1] += posY;
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
    public void manageLinks(){
        if(!worldObj.isRemote) {
            //manage the frontLinkedTransport link
            if (frontLinkedTransport != null) {
                GenericRailTransport frontLink = CommonProxy.getTransportFromUuid(frontLinkedTransport);
                if (frontLink != null) {
                    //to here
                    vectorCache[3][0] = getHitboxPositions()[0]-1;
                    vectorCache[4] = rotatePoint(vectorCache[3], 0, rotationYaw, 0);
                    vectorCache[4][0] += posX;
                    vectorCache[4][2] += posZ;
                    //from here
                    vectorCache[5][0]=frontLink.getHitboxPositions()[(frontLink.backLinkedTransport == this.getPersistentID())?(frontLink.getHitboxPositions().length - 1):0];
                    vectorCache[6] = rotatePoint(vectorCache[5], 0, frontLink.rotationYaw, 0);
                    vectorCache[6][0] += frontLink.posX;
                    vectorCache[6][2] += frontLink.posZ;
                    this.addVelocity(-(vectorCache[4][0] - vectorCache[6][0]) * 0.005,0, -(vectorCache[4][2] - vectorCache[6][2]) * 0.005);
                }
            } else if (isCoupling) {
                vectorCache[3][0] = getHitboxPositions()[0]-2;
                vectorCache[4] = rotatePoint(vectorCache[3], 0, rotationYaw, 0);
                vectorCache[4][0] += posX;
                vectorCache[4][1] += posY;
                vectorCache[4][2] += posZ;
                List list = worldObj.getEntitiesWithinAABBExcludingEntity(this,
                        AxisAlignedBB.getBoundingBox(vectorCache[4][0] - 0.5d, vectorCache[4][1], vectorCache[4][2] - 0.5d,
                                vectorCache[4][0] + 0.5d, vectorCache[4][1] + 2, vectorCache[4][2] +0.5d));

                if (list.size() > 0) {
                    for (Object entity : list) {
                        if (entity instanceof HitboxHandler.MultipartHitbox && !hitboxHandler.hitboxList.contains(entity) && ((GenericRailTransport)worldObj.getEntityByID(((HitboxHandler.MultipartHitbox) entity).parent.getEntityId())).isCoupling) {
                            frontLinkedTransport = ((HitboxHandler.MultipartHitbox) entity).parent.getPersistentID();
                            System.out.println(getEntityId() + " : rollingstock frontLinkedTransport linked : ");
                        }
                    }
                }
            }
            //Manage the backLinkedTransport link
            if (backLinkedTransport != null) {
                GenericRailTransport backLink = CommonProxy.getTransportFromUuid(backLinkedTransport);
                if (backLink != null) {
                    //to here
                    vectorCache[5][0]=getHitboxPositions()[getHitboxPositions().length-1]+1;
                    vectorCache[6] = rotatePoint(vectorCache[5], 0, rotationYaw, 0);
                    vectorCache[6][0] += posX;
                    vectorCache[6][2] += posZ;
                    //from here
                    vectorCache[5][0]=backLink.getHitboxPositions()[(backLink.backLinkedTransport == this.getPersistentID())?(backLink.getHitboxPositions().length - 1):0];
                    vectorCache[6] = rotatePoint(vectorCache[5], 0, backLink.rotationYaw, 0);
                    vectorCache[6][0] += backLink.posX;
                    vectorCache[6][2] += backLink.posZ;
                    this.addVelocity(-(vectorCache[6][0] - vectorCache[6][0]) * 0.005,0, -(vectorCache[6][2] -vectorCache[6][2]) * 0.005);
                }
            } else if (isCoupling) {
                vectorCache[3][0] = getHitboxPositions()[getHitboxPositions().length-1] + 2;
                vectorCache[4] = rotatePoint(vectorCache[3], 0, rotationYaw, 0);
                vectorCache[4][0] += posX;
                vectorCache[4][1] += posY;
                vectorCache[4][2] += posZ;
                List list = worldObj.getEntitiesWithinAABBExcludingEntity(this,
                        AxisAlignedBB.getBoundingBox(vectorCache[4][0] - 0.5d, vectorCache[4][1], vectorCache[4][2] - 0.5d,
                                vectorCache[4][0] + 0.5d, vectorCache[4][1] + 2, vectorCache[4][2] +0.5d));

                if (list.size() > 0) {
                    for (Object entity : list) {
                        if (entity instanceof HitboxHandler.MultipartHitbox && !hitboxHandler.hitboxList.contains(entity) && ((GenericRailTransport)worldObj.getEntityByID(((HitboxHandler.MultipartHitbox) entity).parent.getEntityId())).isCoupling) {
                            backLinkedTransport = ((HitboxHandler.MultipartHitbox) entity).parent.getPersistentID();
                            System.out.println(getEntityId() + " : rollingstock backLinkedTransport linked : ");
                        }
                    }
                }
            }
        }
    }


    /**
     * <h2>Boolean switches</h2>
     * Toggles the defined booleans, this is mostly just used for user input like key press and GUI buttons.
     * Potentially unnecessary, but theoretically more reliable than direct modification of the variables from outside the class.
     */
    public boolean toggleBool(int index){
        switch (index){
            case 4:{
                brake = !brake;
                return true;
            }case 5:{
                lamp.isOn = !lamp.isOn;
                return true;
            }case 6:{
                isLocked = ! isLocked;
                return true;
            }case 7:{
                isCoupling = !isCoupling;
                return true;
            }case 10:{
                isCreative = !isCreative;
                return true;
            }
        }
        return false;
    }

    /**
     * <h2>Permissions handler</h2>
     * Used to check if the player has permission to do whatever it is the player is trying to do. Yes I could be more vague with that.
     *
     * @param player the player attenpting to interact.
     * @param driverOnly can this action only be done by the driver/conductor?
     * @return if the player has permission to continue
     */
    public boolean getPermissions(EntityPlayer player, boolean driverOnly) {
        //if this requires the player to be the driver, and they aren't, just return false before we even go any further.
        if (driverOnly && player.getEntityId() != this.riddenByEntity.getEntityId()){
            return false;
        }

        //be sure admins and owners can do whatever
        if (player.capabilities.isCreativeMode || owner == player.getUniqueID()) {
            return true;
        }

        //if the key is needed, like for trains and freight
        if (isLocked && player.inventory.hasItem(key.getItem())) {
            return true;
        }
        //if a ticket is needed like for passenger cars
        if(isLocked && getRiderOffsets().length>1){
            return player.inventory.hasItem(getTicketSlot().getItem());
        }

        //all else fails, just return if this is locked.
        return !isLocked;

    }

    @Override
    protected void setRotation(float p_70101_1_, float p_70101_2_) {
        this.prevRotationYaw = this.rotationYaw = p_70101_1_;
        this. prevRotationPitch = this.rotationPitch = p_70101_2_;
    }


    public GameProfile getOwner(){
        if (ownerID != 0 && worldObj.getEntityByID(ownerID) instanceof EntityPlayer){
            return ((EntityPlayer) worldObj.getEntityByID(ownerID)).getGameProfile();
        }
        return null;
    }

    /**
     * <h2>Inherited variables</h2>
     * these functions are overridden by classes that extend this so that way the values can be changed indirectly.
     */
    /**returns the lengths from center that represent the offset each bogie should render at*/
    public List<Double> getRenderBogieOffsets(){return new ArrayList<Double>();}
    /**returns the type of transport, for a list of options:
     * @see TrainsInMotion.transportTypes */
    public TrainsInMotion.transportTypes getType(){return null;}
    /**returns the rider offsets, each of the outer arrays represents a new rider seat,
     * the first value of the double[] inside that represents length from center in blocks.
     * the second represents height offset in blocks
     * the third value is for the horizontal offset*/
    public double[][] getRiderOffsets(){return new double[][]{{0,0,0}};}
    /**returns the positions for the hitbox, they are defined by length from center.*/
    public double[] getHitboxPositions(){return new double[]{-1,0,1};}
    /**returns the item of the transport, this should be a static value in the transport's class.*/
    public Item getItem(){return null;}
    /**defines the size of the inventory, not counting any special slots like for fuel.
     * @see TrainsInMotion.inventorySizes*/
    public TrainsInMotion.inventorySizes getInventorySize(){return TrainsInMotion.inventorySizes.THREExTHREE;}
    /**defines the offset for the lamp in X/Y/Z*/
    public Vec3d getLampOffset(){return new Vec3d(0,0,0);}
    /**defines the radius in microblocks that the pistons animate*/
    public float getPistonOffset(){return 0;}
    /**defines smoke positions, the outer array defines each new smoke point, the inner arrays define the X/Y/Z*/
    public float[][] getSmokeOffset(){return new float[][]{{0,0,0,255}};}
    /**defines the length from center of the transport, thus is used for the motion calculation*/
    public double getLengthFromCenter(){return 1d;}
    /**defines the render scale, minecraft's default is 0.0625*/
    public float getRenderScale(){return 0.0625f;}
    /**defines if the transport is explosion resistant*/
    public boolean isReinforced(){return false;}
    /**defines the capacity of the fluidTank tank*/
    public int getTankCapacity(){return 0;}
    /**defines the ID of the owner*/
    public int getOwnerID(){return this.dataWatcher.getWatchableObjectInt(19);}


    /**
     * <h1>Inventory management</h1>
     */

    /**
     * <h2>define filters</h2>
     * this is called on the creation of an entity that need it's inventory filtered, or on the event that the entity's filter is set, like from the GUI.
     * whitelist as true will allow only the defined types or items. while as false will allow anything except the defined types or items.
     * types in most cases will override items because items are always checked last, if at all.
     * itemTypes.ALL is basically just ignored, this is only called when you are not going to filter by type.
     */
    public void setFilter(boolean isWhitelist, Item[] items){
        this.isWhitelist = isWhitelist;
        if (items != null) {
            filter.clear();
            for (Item itm : items){
                filter.add(itm.getUnlocalizedName());
            }
        }
    }

    /**
     * <h2>inventory size</h2>
     * @return the number of slots the inventory should have.
     * if it's a train we have to calculate the size based on the type and the size of inventory its supposed to have.
     * trains get 1 extra slot by default for fuel, steam and nuclear steam get another slot, and if it can take passengers there's another slot, this is added to the base inventory size.
     * if it's not a train or rollingstock, then just return the base amount for a crafting table.
     */
    @Override
    public int getSizeInventory() {
        int size =0;
        if (getType() == TrainsInMotion.transportTypes.STEAM || getType()== TrainsInMotion.transportTypes.NUCLEAR_STEAM){
            size=2;
        } else if(getType() == TrainsInMotion.transportTypes.DIESEL || getType() == TrainsInMotion.transportTypes.ELECTRIC || getType() == TrainsInMotion.transportTypes.NUCLEAR_ELECTRIC || getType() == TrainsInMotion.transportTypes.MAGLEV){
            size++;
        }
        if (getRiderOffsets() != null && getRiderOffsets().length >1){
            size++;
        }
        return size+ (getInventorySize().getCollumn() * getInventorySize().getRow());
    }

    /**
     * <h2>get item</h2>
     * @return the item in the requested slot
     */
    @Override
    public ItemStack getStackInSlot(int slot) {
        if (items == null || slot <0 || slot >= items.size()){
            return null;
        } else {
            return items.get(slot);
        }
    }

    /**
     * <h2>decrease stack size</h2>
     * @return the itemstack with the decreased size. If the decreased size is equal to or less than the current stack size it returns null.
     */
    @Override
    public ItemStack decrStackSize(int slot, int stackSize) {
        if (items!= null && getSizeInventory()>=slot && items.get(slot) != null) {
            ItemStack itemstack;

            if (items.get(slot).stackSize <= stackSize) {
                itemstack = items.get(slot).copy();
                items.set(slot, null);

                return itemstack;
            } else {
                itemstack = items.get(slot).splitStack(stackSize);
                if (items.get(slot).stackSize == 0) {
                    items.set(slot, null);
                }

                return itemstack;
            }
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
        if (items != null && slot >0 && slot < getSizeInventory()) {
            items.set(slot, itemStack);
        }
    }

    /**
     * <h2>name and stack limit</h2>
     * These are grouped together because they are pretty self-explanatory.
     */
    @Override
    public String getInventoryName() {return getItem().getUnlocalizedName() + ".storage";}
    @Override
    public boolean hasCustomInventoryName() {return items != null;}
    @Override
    public int getInventoryStackLimit() {return items!=null?64:0;}

    /**
     * <h2>is Locked</h2>
     * returns if the entity is locked, and if it is, if the player is the owner.
     * This makes sure the inventory can be accessed by anyone if its unlocked and only by the owner when it is locked.
     * if it's a tile entity, it's just another null check to be sure no one crashes.
     */
    @Override
    public boolean isUseableByPlayer(EntityPlayer p_70300_1_) {return getPermissions(p_70300_1_, false);}

    @Override
    public boolean isItemValidForSlot(int slot, ItemStack itemStack) {
        //compensate for specific rollingstock
        switch (getType()) {
            case LOGCAR: {
                for (ItemStack log : logCarrier) {
                    if (log.getItem() == itemStack.getItem()) {
                        return true;
                    }
                }
                return false;
            }
            case COALHOPPER: {
                for (ItemStack coal : coalCarrier) {
                    if (coal.getItem() == itemStack.getItem()) {
                        return true;
                    }
                }
                return false;
            }
        }


        //before we even bother to try and check everything else, check if it's filtered in the first place.
        if (itemStack == null || filter.size() == 0) {
            return true;
        }
        //if we use a whitelist, only return true if the item is in the list.
        if (isWhitelist) {
            return filter.size() != 0 && filter.contains(itemStack.getItem().getUnlocalizedName());
        } else {
            //if it's a blacklist do exactly the same as above but return the opposite value.
            return filter.size() == 0 || !filter.contains(itemStack.getItem().getUnlocalizedName());
        }
    }


    /**
     * <h2>Get Ticket Slot</h2>
     * this just simply returns the itemstack in the ticket slot, assuming there is one.
     */
    public ItemStack getTicketSlot(){
        //if it's a train with a boiler, send backLinkedTransport slot 2.
        if ((getType() == TrainsInMotion.transportTypes.STEAM || getType() == TrainsInMotion.transportTypes.NUCLEAR_STEAM) &&
                getRiderOffsets().length > 1) {
            return items.get(2);
        } else if ((getType() == TrainsInMotion.transportTypes.DIESEL || getType() == TrainsInMotion.transportTypes.ELECTRIC || getType() == TrainsInMotion.transportTypes.NUCLEAR_ELECTRIC || getType() == TrainsInMotion.transportTypes.MAGLEV)
                && getRiderOffsets().length > 1){
            //if it's a train without a boiler, send backLinkedTransport slot 1
            return items.get(1);
        } else if (getRiderOffsets().length > 1){
            //if it's not a train, send backLinkedTransport slot 0
            return items.get(0);
        }
        //if it's not meant to have multiple passengers, send backLinkedTransport null because there is no ticket slot.
        return null;
    }
    /**
     * <h2>Add item to train inventory</h2>
     * custom function for adding items to the train's inventory.
     * similar to a container's TransferStackInSlot function, this will automatically sort an item into the inventory.
     * if there is no room in the inventory for the item, it will drop on the ground.
     */
    public void addItem(ItemStack item){
        for (int i=1; i<getSizeInventory();i++){
            if (i==1){
                if (getType() == TrainsInMotion.transportTypes.STEAM || getType() == TrainsInMotion.transportTypes.NUCLEAR_STEAM){
                    i++;
                }
                if (getRiderOffsets().length>1){
                    i++;
                }
            }

            if (getStackInSlot(i) ==null){
                setInventorySlotContents(i, item);
                return;
            } else if (getStackInSlot(i).getItem() == item.getItem() &&
                    item.stackSize + items.get(i).stackSize <= item.getMaxStackSize()){
                setInventorySlotContents(i, new ItemStack(item.getItem(), item.stackSize + items.get(i).stackSize));
                return;
            }
        }
        dropItem(item.getItem(), item.stackSize);
    }

    /**
     * <h2>inventory percentage count</h2>
     * calculates percentage of inventory used then returns a value based on the intervals.
     * for example if the inventory is half full and the intervals are 100, it returns 50. or if the intervals were 90 it would return 45.
     */
    public int calculatePercentageUsed(int indexes){
        if (items == null){
            return 0;
        }
        float i=0;
        for (ItemStack item : items){
            if (item != null && item.stackSize >0){
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
            if (i>= index && items.get(i) != null && items.get(i).stackSize>0){
                return items.get(i);
            }
        }
        return getFirstBlock(index>0?index-1:0);
    }

    /**
     * <h2>unused</h2>
     * we have to initialize these values, but due to the design of the entity we don't actually use them.
     */
    /**normally used to drop the inventory content when the GUI is closed, but we don't do that.*/
    @Override
    public ItemStack getStackInSlotOnClosing(int p_70304_1_) {return null;}
    @Override
    public void markDirty() {}
    /**called when the inventory GUI is opened*/
    @Override
    public void openInventory() {}
    /**called when the inventory GUI is closed*/
    @Override
    public void closeInventory() {}


    /**
     * <h1>Fluid Management</h1>
     */
    /**Returns true if the given fluid can be extracted.*/
    @Override
    public boolean canDrain(ForgeDirection from, Fluid resource){return fluidTank != null && fluidTank.amount>0 && (fluidTank.getFluid() == resource || resource == null);}
    /**Returns true if the given fluid can be inserted into the fluid tank.*/
    @Override
    public boolean canFill(ForgeDirection from, Fluid resource){return getTankCapacity()>0 && (fluidTank == null || fluidTank.getFluid() == resource);}
    //drain a set amount
    @Override
    public FluidStack drain(ForgeDirection from, int drain, boolean doDrain){
        if (getTankCapacity() <1 || fluidTank == null || fluidTank.amount <1){
            return null;
        } else {
            if (fluidTank.amount <= drain){
                FluidStack toReturn = fluidTank.copy();
                if (doDrain){
                    fluidTank = null;
                    this.dataWatcher.updateObject(20,0);
                }
                return toReturn;
            } else {
                FluidStack toReturn = fluidTank.copy();
                toReturn.amount -= drain;
                if (doDrain){
                    fluidTank.amount -= drain;
                    this.dataWatcher.updateObject(20, fluidTank.amount);
                }
                return toReturn;
            }
        }
    }
    //drain only if its a specific fluidTank
    @Override
    public FluidStack drain(ForgeDirection from, FluidStack resource, boolean doDrain){
        if (getTankCapacity() <1 || fluidTank == null || fluidTank.getFluid() != resource.getFluid() || fluidTank.amount<1){
            return null;
        } else {
            if (fluidTank.amount <= resource.amount){
                FluidStack toReturn = fluidTank.copy();
                if (doDrain){
                    fluidTank = null;
                    this.dataWatcher.updateObject(20,0);
                }
                return toReturn;
            } else {
                FluidStack toReturn = fluidTank.copy();
                toReturn.amount -= resource.amount;
                if (doDrain){
                    fluidTank.amount -= resource.amount;
                    this.dataWatcher.updateObject(20, fluidTank.amount);
                }
                return toReturn;
            }
        }
    }
    /**returns the amount of fluid in the tank. 0 if the tank is null*/
    public int getTankAmount(){
        return fluidTank !=null? fluidTank.amount:0;
    }

    @Override
    public int fill(ForgeDirection from, FluidStack resource, boolean doFill){
        if (getTankCapacity() <1 || !(fluidTank == null || fluidTank.getFluid() == resource.getFluid())){
            return 0;
        }
        if (fluidTank == null){
            if (resource.amount > getTankCapacity()) {
                resource.amount = getTankCapacity();
            }
            if (doFill) {
                fluidTank = resource;
                this.dataWatcher.updateObject(20, fluidTank.amount);
            }
            return resource.amount;

        } else if (getTankCapacity() + fluidTank.amount < resource.amount){
            if (doFill){
                fluidTank.amount += resource.amount;
                this.dataWatcher.updateObject(20, fluidTank.amount);
            }
            return resource.amount;
        } else {
            int volume = getTankCapacity() - fluidTank.amount;
            if (doFill){
                fluidTank.amount += volume;
                this.dataWatcher.updateObject(20, fluidTank.amount);
            }
            return volume;
        }
    }
    /**returns the list of fluid tanks and their capacity.*/
    @Override
    public FluidTankInfo[] getTankInfo(ForgeDirection from){
        return getTankCapacity()<1?null:new FluidTankInfo[]{new FluidTankInfo(fluidTank, getTankCapacity())};
    }



    /**
     * <h3> combine itemstacks</h3>
     * combines multiple arrays of itemstacks into a single array,
     * this allows us to create an array comprised of multiple sets of itemstacks, since java doesn't normally allow for this behavior.
     */
    private static ArrayList<ItemStack> combineStacks(ArrayList<ItemStack> oldStacks, ArrayList<ItemStack> newStacks){
        ArrayList<ItemStack> items = new ArrayList<ItemStack>();
        items.addAll(oldStacks);
        items.addAll(newStacks);
        return items;
    }




}