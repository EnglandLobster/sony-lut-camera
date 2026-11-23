package io.github.englandlobster.lutcamera;

public final class SessionPolicyTest {
    private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static void main(String[] args){
        require(SessionPolicy.idleMinutes(-1)==3&&SessionPolicy.idleMinutes(7)==3,"unknown idle setting is 3 minutes");
        require(SessionPolicy.nextIdle(3)==0&&SessionPolicy.nextIdle(0)==1&&SessionPolicy.nextIdle(1)==3,"idle cycles 3 -> off -> 1 -> 3");
        require(SessionPolicy.idleMillis(0)==0&&SessionPolicy.idleMillis(1)==60000L&&SessionPolicy.idleMillis(3)==180000L,"idle millis");
        require(SessionPolicy.idleLabel(0).equals("关")&&SessionPolicy.idleLabel(3).equals("3 分钟"),"idle labels");
        require(SessionPolicy.autoEnter(true,true,false,true),"fresh launch opens shooting");
        require(!SessionPolicy.autoEnter(false,true,false,true),"setting off stays in library");
        require(!SessionPolicy.autoEnter(true,false,false,true),"return from shooting stays in library");
        require(!SessionPolicy.autoEnter(true,true,true,true),"unfinished save keeps library");
        require(!SessionPolicy.autoEnter(true,true,false,false),"no LUT keeps library");
        require(SessionPolicy.exitKey(595,0)&&SessionPolicy.exitKey(0,595),"trash key by key or scan code");
        require(!SessionPolicy.exitKey(514,514)&&!SessionPolicy.exitKey(207,207),"MENU and PLAY are not the exit key");
        SessionPolicy.Idle idle=new SessionPolicy.Idle(180000L,1000L);
        require(!idle.due(180999L)&&idle.remaining(180999L)==1,"not due before timeout");
        require(idle.due(181000L),"due at timeout");
        idle.touch(100000L);require(!idle.due(181000L)&&idle.remaining(181000L)==99000L,"activity re-arms");
        SessionPolicy.Idle off=new SessionPolicy.Idle(0,0);require(!off.enabled()&&!off.due(Long.MAX_VALUE),"off never exits");
        System.out.println("SessionPolicyTest PASS: idle options, re-arm, auto-enter guards, trash key");
    }
}
