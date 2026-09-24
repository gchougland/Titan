package com.hexvane.titan.dunewyrm;

import com.hexvane.titan.spawn.TitanTerrainProbe;
import com.hexvane.titan.ik.GroundSampler;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

/** Read-only outdoor surface sampling. Never chooses a cave floor under a roof. */
public final class DunewyrmTerrain {
    private DunewyrmTerrain() { }

    public static double surface(ChunkStore chunks, double x, double z) {
        int y=TitanTerrainProbe.surfaceY(chunks,(int)Math.floor(x),(int)Math.floor(z));
        return y==TitanTerrainProbe.NO_SURFACE ? Double.NaN : y+1.0;
    }

    /** Lift the broad head over steps instead of treating nearby ground as a wall. */
    public static double headSurface(ChunkStore chunks, double x, double z) {
        double highest=Double.NaN;
        for(int bx=(int)Math.floor(x-2.5);bx<x+2.5;bx++) for(int bz=(int)Math.floor(z-2.5);bz<z+2.5;bz++) {
            double height=surface(chunks,bx,bz);
            if(!GroundSampler.isValid(height)) return Double.NaN;
            highest=Double.isNaN(highest)?height:Math.max(highest,height);
        }
        return highest;
    }

    public static boolean clearHead(ChunkStore chunks, double x, double y, double z) {
        return obstruction(chunks,x,y,z)==0;
    }

    /** Continuous penetration cost: a small step out of a tree must reduce it. */
    public static double obstruction(ChunkStore chunks, double x, double y, double z) {
        double cost=0;
        for(int bx=(int)Math.floor(x-2.5);bx<x+2.5;bx++)
            for(int bz=(int)Math.floor(z-2.5);bz<z+2.5;bz++)
                for(int by=(int)Math.floor(y);by<y+5;by++) {
                    double height=Math.min(y+5,by+1)-Math.max(y,by);
                    double depth=Math.min(Math.min(x+2.5-bx,bx+3.5-x),Math.min(z+2.5-bz,bz+3.5-z));
                    if(height>0 && depth>0 && GroundSampler.isSolid(chunks,bx,by,bz)) cost+=depth*depth*height;
                }
        return cost;
    }

    /** Sweep short steps so charges cannot jump through a trunk between ticks. */
    public static double movementHeight(ChunkStore chunks, double ox, double oy, double oz,
                                        double x, double z, boolean blockTallClimbs) {
        int steps=Math.max(1,(int)Math.ceil(Math.hypot(x-ox,z-oz)/.25));
        double y=oy, previous=obstruction(chunks,ox,oy,oz);
        for(int i=1;i<=steps;i++) {
            double nx=ox+(x-ox)*i/steps,nz=oz+(z-oz)*i/steps;
            double surface=headSurface(chunks,nx,nz);
            if(!GroundSampler.isValid(surface) || (blockTallClimbs && surface>y+DunewyrmTuning.MAX_CLIMB)) return Double.NaN;
            double ny=Math.max(surface,oy-DunewyrmTuning.MAX_DROP);
            double next=obstruction(chunks,nx,ny,nz);
            if(next>0 && (previous==0 || next>=previous-1e-8)) return Double.NaN;
            previous=next;y=ny;
        }
        return y;
    }
}
