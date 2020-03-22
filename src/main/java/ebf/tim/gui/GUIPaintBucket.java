package ebf.tim.gui;


import ebf.tim.TrainsInMotion;
import ebf.tim.api.skin;
import ebf.tim.entities.GenericRailTransport;
import ebf.tim.items.ItemTransport;
import ebf.tim.models.Bogie;
import ebf.tim.models.RenderEntity;
import ebf.tim.networking.PacketPaint;
import ebf.tim.utility.ClientProxy;
import ebf.tim.utility.DebugUtil;
import ebf.tim.utility.EventManager;
import ebf.tim.utility.RailUtility;
import ebf.timsquared.entities.rollingstock.EntityGTAX13000GallonTanker;
import ebf.timsquared.entities.trains.EntityBrigadelok080;
import fexcraft.tmt.slim.ModelBase;
import fexcraft.tmt.slim.TextureManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.model.ModelBook;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.entity.Entity;
import net.minecraft.inventory.ContainerEnchantment;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnchantmentNameParts;
import net.minecraft.util.MathHelper;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.util.glu.Project;

import java.util.*;

/**
 *@author Oskiek
 */
public class GUIPaintBucket extends GuiScreen {


    public GuiButton buttonLeft;
    public GuiButton buttonRight;
    public GuiButton buttonApply;
    public GuiButton buttonClose;
    public GuiButton buttonSkinDropdown;

    public GuiButton buttonRecolor;
    public GuiButton buttonLivery;

    List<String>  skinList = new ArrayList<>();
    skin currentSkin;

    int page = 0;

    int guiScreen=0;

    public static int guiTop;
    public static int guiLeft;
    public GenericRailTransport entity;

    public GUIPaintBucket(GenericRailTransport t){
        entity=t;

    }

    @Override
    public void initGui() {
        super.initGui();
        if(skinList.size()==0) {
            skinList = new ArrayList<>();
            List<skin> skins = new ArrayList<>(entity.getSkinList(Minecraft.getMinecraft().thePlayer, true).values());
            Collections.sort(skins, new Comparator<skin>() {
                @Override
                public int compare(skin o1, skin o2) {
                    return o1.id-o2.id;
                }
            });
            for(skin s:skins){
                skinList.add(s.modid + ":" + s.name);
            }
        }

        Keyboard.enableRepeatEvents(true);
    }

    @Override
    public void updateScreen() {}

    public static int percentTop(int value){return (int)(guiTop*(value*0.01f));}
    public static int percentLeft(int value){return (int)(guiLeft*(value*0.01f));}

    public int field_147073_u;

    @Override
    public void drawScreen(int parWidth, int parHeight, float p_73863_3_) {
        super.drawScreen(parWidth, parHeight, p_73863_3_);

        currentSkin = entity.getTextureByID(Minecraft.getMinecraft().thePlayer,true, skinList.get(page));
        guiLeft=new ScaledResolution(Minecraft.getMinecraft(), Minecraft.getMinecraft().displayWidth, Minecraft.getMinecraft().displayHeight).getScaledWidth();
        guiTop=new ScaledResolution(Minecraft.getMinecraft(), Minecraft.getMinecraft().displayWidth, Minecraft.getMinecraft().displayHeight).getScaledHeight();

        //initGui();
        if(currentSkin==null){return;}

        switch(guiScreen) {
            case 0:{defineButtons();guiSkinSelect();break;}
        }
    }

    @Override
    protected void actionPerformed(GuiButton parButton) {
        if (parButton==buttonApply) {
            if(guiScreen==0) {//skin select
                TrainsInMotion.keyChannel.sendToServer(new PacketPaint(skinList.get(page), entity.getEntityId()));
            } else if(guiScreen==1){//color select

            } else {//livery select

            }
            entity.renderData.needsModelUpdate=true;
        }
        else if(parButton==buttonClose){
            mc.displayGuiScreen(null);
        }
        else if (parButton==buttonLeft) {
            page = (page <= 0 ? entity.getSkinList(Minecraft.getMinecraft().thePlayer, true).keySet().size() -1: page - 1);
            currentSkin=entity.getSkinList(Minecraft.getMinecraft().thePlayer, true).get(skinList.get(page));
            DebugUtil.println(page, currentSkin.name);
        }
        else if (parButton==buttonRight) {
            page = (page+1 >= entity.getSkinList(Minecraft.getMinecraft().thePlayer, true).keySet().size() ? 0 : page + 1);
            currentSkin=entity.getSkinList(Minecraft.getMinecraft().thePlayer, true).get(skinList.get(page));
            DebugUtil.println(page, currentSkin.name);
        }
    }


    @Override
    public boolean doesGuiPauseGame() {return true;}



    public void defineButtons(){
        switch (guiScreen){
            case 0:{
                buttonList =new ArrayList();
                buttonList.add(buttonLeft = new GuiButton(-1, percentLeft(15)-10,percentTop(56), 20,20,"<<"));//left
                buttonList.add(buttonRight = new GuiButton(-1, percentLeft(75)-10,percentTop(56), 20,20,">>"));//right
                buttonList.add(buttonApply = new GuiButton(-1, percentLeft(83)-16,percentTop(56), 32,20,"Apply"));//apply
                buttonList.add(buttonClose = new GuiButton(-1, percentLeft(75)-16,percentTop(45), 64,30,"Close"));//close
                buttonApply.visible=true;
                break;
            }
        }
    }




    public void guiSkinSelect(){

        GL11.glPushMatrix();
        GL11.glColor4f(1F, 1F, 1F, 0.5F);
        float offsetFromScreenLeft = width * 0.5f;

        int longest =fontRendererObj.getStringWidth(currentSkin.name);

        if(currentSkin.getDescription()!=null) {
            for (String s : currentSkin.getDescription()){
                if(fontRendererObj.getStringWidth(s)>longest){
                    longest=fontRendererObj.getStringWidth(s);
                }
            }
        }
        EventManager.drawTooltipBox((int)(width*0.125f),(int)(height*0.65f),(int)(width*0.75f),(int)(height*0.345f),  ClientProxy.WAILA_BGCOLOR, ClientProxy.WAILA_GRADIENT1, ClientProxy.WAILA_GRADIENT2,100);

        EventManager.drawTooltipBox((int)(width*0.175f),(int)(height*0.56f),(int)(width*0.5525f),(int)(height*0.085f),  ClientProxy.WAILA_BGCOLOR, ClientProxy.WAILA_GRADIENT1, ClientProxy.WAILA_GRADIENT2,100);

        fontRendererObj.drawString(RailUtility.translate(currentSkin.name),
                (int)(offsetFromScreenLeft - fontRendererObj.getStringWidth(currentSkin.name)*0.65f),
                (int)(height*0.59f),ClientProxy.WAILA_FONTCOLOR,false);

        if(currentSkin.getDescription()!=null) {
            for(int i=0; i<currentSkin.getDescription().length;i++) {
                fontRendererObj.drawString(currentSkin.getDescription()[i],
                        (int) (offsetFromScreenLeft - fontRendererObj.getStringWidth(currentSkin.getDescription()[i]) * 0.5f),
                        (int) ((height * 0.1f) * 7)+(10*i), ClientProxy.WAILA_FONTCOLOR, false);
            }
        }



        EventManager.drawTooltipBox((int)(width*0.125f),(int)(height*0.2f),(int)(width*0.35f),(int)(height*0.35f),  ClientProxy.WAILA_BGCOLOR, ClientProxy.WAILA_GRADIENT1, ClientProxy.WAILA_GRADIENT2,100);

        //my additional scaling
        float scale = entity.getHitboxSize()[0];
        if(scale!=0){
            scale = 0.5f/(scale /0.5f);
        }

        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        GL11.glPushMatrix();
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glLoadIdentity();
        GL11.glTranslatef(-0.4f,0.05f,-1f);

        Project.gluPerspective(90.0F, 1.3333334F, 0.05f, 2);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glLoadIdentity();
        RenderHelper.enableStandardItemLighting();
        GL11.glTranslatef(0,0,-0.4f);

        GL11.glRotatef(15,1,0,0);
        GL11.glRotatef(field_147073_u+=1,0,1,0);
        GL11.glScalef(scale,scale,scale);

        ClientProxy.transportRenderer.render(entity,0,0,0,0, true,
                currentSkin);
        RenderHelper.disableStandardItemLighting();
        GL11.glPopMatrix();
        GL11.glPopMatrix();

    }


}