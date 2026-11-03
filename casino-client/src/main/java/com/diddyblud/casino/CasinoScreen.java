package com.diddyblud.casino;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** A true client screen; no chest inventory is opened. */
public final class CasinoScreen extends Screen {
    private boolean auctions = false;
    private String stake = "1k";
    private String auctionTitle = "";
    private EditBox titleField;
    private static final int BG = 0xEE101323;
    private static final int PANEL = 0xFF222A40;
    private static final int GOLD = 0xFFF8C964;
    private static final int WHITE = 0xFFE4ECFF;

    public CasinoScreen(){super(Component.literal("Diddyblud Arcade"));}

    @Override protected void init(){
        int cx=width/2;
        int left=cx-156;
        addRenderableWidget(Button.builder(Component.literal("GAMES"),b->{
            auctions=false;rebuild();
        }).bounds(left+10,43,137,20).build());
        addRenderableWidget(Button.builder(Component.literal("AUCTIONS"),b->{
            auctions=true;rebuild();
        }).bounds(left+163,43,137,20).build());

        if(!auctions){
            String[] stakes={"100","1k","10k","100k","1m"};
            for(int i=0;i<stakes.length;i++){
                String value=stakes[i];
                addRenderableWidget(Button.builder(Component.literal((stake.equals(value)?"● ":"")+ "$"+value),b->{
                    stake=value;rebuild();
                }).bounds(left+10+i*60,86,57,20).build());
            }
            gameButton(left+12,139,"COIN FLIP","coin");
            gameButton(left+111,139,"HIGH ROLL","roll");
            gameButton(left+210,139,"LUCKY 7s","sevens");
        }else{
            titleField=new EditBox(font,left+15,100,282,20,Component.literal("Auction title"));
            titleField.setMaxLength(64);
            titleField.setValue(auctionTitle);
            titleField.setResponder(value->auctionTitle=value);
            addRenderableWidget(titleField);
            addRenderableWidget(Button.builder(Component.literal("START AUCTION"),b->{
                String safe=auctionTitle.trim().replaceAll("[\\r\\n\\t]"," ");
                if(safe.isEmpty())return;
                send("gamble auction start "+safe);
            }).bounds(left+15,133,138,22).build());
            addRenderableWidget(Button.builder(Component.literal("STOP AUCTION"),b->send("gamble auction stop"))
                .bounds(left+161,133,137,22).build());
            addRenderableWidget(Button.builder(Component.literal("VIEW PAYMENT LOG"),b->send("gamble auction log"))
                .bounds(left+15,168,283,22).build());
        }
        addRenderableWidget(Button.builder(Component.literal("CLOSE"),b->onClose())
            .bounds(cx-45,height-48,90,20).build());
    }

    private void gameButton(int x,int y,String label,String game){
        addRenderableWidget(Button.builder(Component.literal(label),
            b->send("gamble play "+game+" "+stake)).bounds(x,y,89,30).build());
    }

    private void rebuild(){clearWidgets();init();}

    private void send(String command){
        var client=this.minecraft;
        if(client!=null && client.getConnection()!=null){
            client.getConnection().sendCommand(command);
            client.gui.setScreen(null);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics,int mouseX,int mouseY,float delta){
        super.extractRenderState(graphics,mouseX,mouseY,delta);
        int left=width/2-156;
        graphics.fill(left-5,18,left+317,height-60,BG);
        graphics.fill(left,21,left+312,38,PANEL);
        graphics.centeredText(font,Component.literal("DIDDYBLUD  |  ARCADE"),width/2,25,GOLD);
        graphics.fill(left+10,70,left+302,72,0xFF53617B);
        if(!auctions){
            graphics.text(font,"CHOOSE YOUR STAKE",left+12,76,WHITE,true);
            graphics.text(font,"Fair game odds · in-game currency only",left+12,116,0xFFABB9D8,true);
            graphics.text(font,"50%  |  2x",left+23,178,GOLD,true);
            graphics.text(font,"1 in 3  |  3x",left+121,178,GOLD,true);
            graphics.text(font,"1 in 8  |  8x",left+216,178,GOLD,true);
            graphics.text(font,"Winnings and balance are handled by the server.",left+12,213,WHITE,true);
        }else{
            graphics.text(font,"AUCTION TITLE",left+15,83,WHITE,true);
            graphics.text(font,"Payments made via /pay are tracked while active.",left+15,198,WHITE,true);
            graphics.text(font,"The log appears in chat after you select View.",left+15,212,0xFFABB9D8,true);
            graphics.text(font,"Payments are transfers, not refundable bids.",left+15,226,GOLD,true);
        }
    }
}
