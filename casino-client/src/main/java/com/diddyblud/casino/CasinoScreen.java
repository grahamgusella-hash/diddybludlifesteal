package com.diddyblud.casino;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.Base64;

/** Full client-side casino and auction panel; the Paper server owns all auction and money state. */
public final class CasinoScreen extends Screen {
    private static CasinoScreen current;
    private boolean auctions=false;
    private String stake="1k";
    private String auctionTitle="";
    private String auctionDuration="5";
    private String minimumBid="100";
    private EditBox titleField, durationField, bidField;
    private String status="loading";
    private String itemName="";
    private long endsAt=0, minBid=0, highest=0;
    private int bidCount=0, refreshTicks=0;
    private final List<String> bidders=new ArrayList<>();
    private static final int BG=0xEE101323, PANEL=0xFF222A40;
    private static final int GOLD=0xFFF8C964, WHITE=0xFFE4ECFF, FAINT=0xFFABB9D8;

    public CasinoScreen(){super(Component.literal("Diddyblud Arcade"));}

    public static boolean receiveState(String raw){
        if(!raw.startsWith("DIDDY_AUC|"))return false;
        var client=net.minecraft.client.Minecraft.getInstance();
        client.execute(()->{
            if(current!=null)current.updateAuction(raw);
        });
        return true;
    }

    private static String decode(String raw){
        try{
            return new String(Base64.getUrlDecoder().decode(raw),StandardCharsets.UTF_8);
        }catch(IllegalArgumentException ignored){return "Unknown";}
    }
    private void updateAuction(String raw){
        try{
            String[] parts=raw.split("\\|",-1);
            if(parts.length==2 && parts[1].equals("none")){
                status="none";itemName="";endsAt=0;minBid=0;highest=0;bidCount=0;bidders.clear();
            }else if(parts.length>=7){
                status=parts[1];
                itemName=decode(parts[2]);
                endsAt=Long.parseLong(parts[3]);
                minBid=Long.parseLong(parts[4]);
                highest=Long.parseLong(parts[5]);
                bidCount=Integer.parseInt(parts[6]);
                bidders.clear();
                for(int i=7;i<parts.length;i++){
                    String[] entry=parts[i].split(",",2);
                    if(entry.length==2)bidders.add(decode(entry[0])+"  $"+money(Long.parseLong(entry[1])));
                }
            }
            if(auctions)rebuild();
        }catch(RuntimeException ignored){status="error";}
    }

    @Override protected void init(){
        current=this;
        int cx=width/2, left=cx-165;
        addRenderableWidget(Button.builder(Component.literal("GAMES"),b->{auctions=false;rebuild();})
            .bounds(left+10,43,148,20).build());
        addRenderableWidget(Button.builder(Component.literal("AUCTIONS"),b->{
            auctions=true;status="loading";rebuild();requestState();
        }).bounds(left+173,43,148,20).build());

        if(!auctions){
            String[] stakes={"100","1k","10k","100k","1m"};
            for(int i=0;i<stakes.length;i++){
                String value=stakes[i];
                addRenderableWidget(Button.builder(Component.literal((stake.equals(value)?"● ":"")+"$"+value),b->{
                    stake=value;rebuild();
                }).bounds(left+10+i*64,86,61,20).build());
            }
            gameButton(left+11,139,"COIN FLIP","coin");
            gameButton(left+120,139,"HIGH ROLL","roll");
            gameButton(left+229,139,"LUCKY 7s","sevens");
        }else if(status.equals("active")){
            addRenderableWidget(Button.builder(Component.literal("+1 MIN"),b->sendAuction("addtime 1"))
                .bounds(left+9,148,98,22).build());
            addRenderableWidget(Button.builder(Component.literal("+5 MIN"),b->sendAuction("addtime 5"))
                .bounds(left+116,148,98,22).build());
            addRenderableWidget(Button.builder(Component.literal("+10 MIN"),b->sendAuction("addtime 10"))
                .bounds(left+223,148,98,22).build());
            addRenderableWidget(Button.builder(Component.literal("STOP AUCTION"),b->sendAuction("stop"))
                .bounds(left+9,181,152,22).build());
            addRenderableWidget(Button.builder(Component.literal("REFRESH BIDDERS"),b->requestState())
                .bounds(left+169,181,152,22).build());
        }else{
            titleField=new EditBox(font,left+9,98,310,20,Component.literal("Item being auctioned"));
            titleField.setMaxLength(64);
            titleField.setValue(auctionTitle);
            titleField.setResponder(v->auctionTitle=v);
            addRenderableWidget(titleField);
            durationField=new EditBox(font,left+9,145,147,20,Component.literal("Duration (minutes)"));
            durationField.setMaxLength(3);
            durationField.setValue(auctionDuration);
            durationField.setResponder(v->auctionDuration=v);
            addRenderableWidget(durationField);
            bidField=new EditBox(font,left+173,145,146,20,Component.literal("Minimum bid"));
            bidField.setMaxLength(13);
            bidField.setValue(minimumBid);
            bidField.setResponder(v->minimumBid=v);
            addRenderableWidget(bidField);
            addRenderableWidget(Button.builder(Component.literal("START AUCTION"),b->startAuction())
                .bounds(left+9,181,202,22).build());
            addRenderableWidget(Button.builder(Component.literal("REFRESH"),b->requestState())
                .bounds(left+222,181,97,22).build());
        }
        addRenderableWidget(Button.builder(Component.literal("CLOSE"),b->onClose())
            .bounds(cx-45,height-45,90,20).build());
    }

    @Override public void removed(){
        if(current==this)current=null;
        super.removed();
    }

    private void gameButton(int x,int y,String label,String game){
        addRenderableWidget(Button.builder(Component.literal(label),
            b->send("gamble play "+game+" "+stake,true)).bounds(x,y,100,30).build());
    }
    private static String money(long amount){return String.format(Locale.US,"%,d",amount);}
    private void rebuild(){clearWidgets();init();}

    private void startAuction(){
        String title=auctionTitle.trim().replaceAll("[\\r\\n\\t|]"," ");
        if(title.isEmpty()||title.length()>64)return;
        try{
            int minutes=Integer.parseInt(auctionDuration.trim());
            long minimum=Long.parseLong(minimumBid.trim());
            if(minutes<1||minutes>120||minimum<1||minimum>1_000_000_000_000L)return;
            sendAuction("start "+minutes+" "+minimum+" "+title);
        }catch(NumberFormatException ignored){}
    }
    private void sendAuction(String args){
        send("gamble auction "+args,false);
        requestState();
    }
    private void requestState(){send("gamble auction state",false);}
    private void send(String command,boolean close){
        var client=this.minecraft;
        if(client!=null&&client.getConnection()!=null){
            client.getConnection().sendCommand(command);
            if(close)client.gui.setScreen(null);
        }
    }
    @Override public void tick(){
        super.tick();
        if(auctions && ++refreshTicks>=100){
            refreshTicks=0;
            requestState();
        }
    }
    private String remaining(){
        if(endsAt<=0)return "Untimed";
        long sec=Math.max(0,(endsAt-System.currentTimeMillis()+999)/1000);
        return (sec/60)+"m "+(sec%60)+"s";
    }

    @Override public void extractBackground(GuiGraphicsExtractor graphics,int mouseX,int mouseY,float delta){
        graphics.fillGradient(0,0,width,height,0xF2141125,0xF20C172A);
        int left=width/2-165;
        graphics.fill(left-6,18,left+336,Math.max(260,height-60),BG);
        graphics.fill(left,21,left+330,38,PANEL);
        graphics.centeredText(font,Component.literal("DIDDYBLUD  |  CASINO"),width/2,25,GOLD);
        graphics.fill(left+9,70,left+321,72,0xFF53617B);
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics,int mouseX,int mouseY,float delta){
        super.extractRenderState(graphics,mouseX,mouseY,delta);
        int left=width/2-165;
        if(!auctions){
            graphics.text(font,"CHOOSE YOUR STAKE",left+11,76,WHITE,true);
            graphics.text(font,"Fair odds · in-game money only",left+11,117,FAINT,true);
            graphics.text(font,"50% / 2x",left+22,178,GOLD,true);
            graphics.text(font,"1 in 3 / 3x",left+132,178,GOLD,true);
            graphics.text(font,"1 in 8 / 8x",left+245,178,GOLD,true);
            graphics.text(font,"The server determines all bets and payouts.",left+11,213,WHITE,true);
        }else if(status.equals("active")){
            graphics.text(font,"LIVE AUCTION",left+11,78,GOLD,true);
            graphics.text(font,"Item: "+shorten(itemName,33),left+11,91,WHITE,true);
            graphics.text(font,"TIME LEFT: "+remaining(),left+11,108,GOLD,true);
            graphics.text(font,"Minimum: $"+money(minBid)+"    Highest: $"+money(highest),left+11,123,WHITE,true);
            graphics.text(font,"BIDDERS / PAYMENTS ("+bidCount+")",left+11,215,GOLD,true);
            for(int i=0;i<bidders.size()&&i<8;i++)
                graphics.text(font,bidders.get(i),left+15,230+i*12,WHITE,true);
            graphics.text(font,"Bids use /pay <host> <amount>. Payments are final.",left+11,335,FAINT,true);
        }else{
            graphics.text(font,"AUCTION SETUP",left+11,77,GOLD,true);
            graphics.text(font,"ITEM / TITLE",left+11,88,WHITE,true);
            graphics.text(font,"DURATION (MINUTES, 1-120)",left+9,131,FAINT,true);
            graphics.text(font,"MINIMUM BID ($)",left+173,131,FAINT,true);
            graphics.text(font,"Bidders use /pay to submit bids.",left+11,219,WHITE,true);
            graphics.text(font,"Payments are not automatically refunded.",left+11,233,GOLD,true);
            if(status.equals("ended"))graphics.text(font,"Previous auction finished.",left+11,249,FAINT,true);
        }
    }
    private static String shorten(String str,int limit){
        return str.length()>limit?str.substring(0,limit-3)+"...":str;
    }
}
