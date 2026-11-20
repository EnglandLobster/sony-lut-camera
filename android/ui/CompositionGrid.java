package io.github.englandlobster.lutcamera;

/** Composition guides over the live viewfinder. Drawing only: the camera,
 * capture and saved files are unaffected. */
enum CompositionGrid {
    OFF("off","关"),
    THIRDS("thirds","九宫格"),
    THIRDS_DIAGONAL("thirds-diagonal","九宫格＋对角线"),
    SQUARE("square","方形网格"),
    CENTER("center","中心十字");

    /** Stable preference value. */
    final String key;
    final String label;
    CompositionGrid(String key,String label){this.key=key;this.label=label;}

    static CompositionGrid decode(String key){
        if(key!=null)for(CompositionGrid g:values())if(g.key.equals(key))return g;
        return OFF;
    }
    CompositionGrid step(int direction){
        CompositionGrid[] all=values();
        return all[((ordinal()+direction)%all.length+all.length)%all.length];
    }
    /** Line segments {x0,y0,x1,y1} in 0..1 of the 3:2 capture frame. */
    float[][] lines(){
        switch(this){
            case THIRDS:return thirds(false);
            case THIRDS_DIAGONAL:return thirds(true);
            case SQUARE:{
                // 6×4 cells are square on the 3:2 frame.
                float[][] out=new float[8][];int n=0;
                for(int i=1;i<6;i++)out[n++]=new float[]{i/6f,0,i/6f,1};
                for(int i=1;i<4;i++)out[n++]=new float[]{0,i/4f,1,i/4f};
                return out;
            }
            case CENTER:{
                // Equal arm length on screen: the frame is 3 wide by 2 high.
                float ax=0.03f,ay=ax*1.5f;
                return new float[][]{{.5f-ax,.5f,.5f+ax,.5f},{.5f,.5f-ay,.5f,.5f+ay}};
            }
            default:return new float[0][];
        }
    }
    private static float[][] thirds(boolean diagonals){
        float a=1/3f,b=2/3f;
        float[][] grid={{a,0,a,1},{b,0,b,1},{0,a,1,a},{0,b,1,b}};
        if(!diagonals)return grid;
        return new float[][]{grid[0],grid[1],grid[2],grid[3],{0,0,1,1},{1,0,0,1}};
    }
}
