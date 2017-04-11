package edu.teco.smartlambda.demo;

import edu.teco.smartlambda.execution.LambdaParameter;

public class Parameters implements LambdaParameter {
    private String arrangement = "percussion";
    private int minBars = 128;
    private int maxBars = 256;
    private int barsStep = 16;
    private int beatsPerBar = 4;
    private int ticksPerBeat = 12;
    private int maxVelocity = 1000;
    private int bpm = 124;
    private String groove = "100";

    public int getBeatsPerBar() {
        return beatsPerBar;
    }

    public void setBeatsPerBar(int beatsPerBar) {
        this.beatsPerBar = beatsPerBar;
    }

    public int getTicksPerBeat() {
        return ticksPerBeat;
    }

    public void setTicksPerBeat(int ticksPerBeat) {
        this.ticksPerBeat = ticksPerBeat;
    }

    public int getMaxVelocity() {
        return maxVelocity;
    }

    public void setMaxVelocity(int maxVelocity) {
        this.maxVelocity = maxVelocity;
    }

    public String getArrangement() {
        return arrangement;
    }

    public void setArrangement(String arrangement) {
        this.arrangement = arrangement;
    }

    public int getMinBars() {
        return minBars;
    }

    public void setMinBars(int minBars) {
        this.minBars = minBars;
    }

    public int getMaxBars() {
        return maxBars;
    }

    public void setMaxBars(int maxBars) {
        this.maxBars = maxBars;
    }

    public int getBarsStep() {
        return barsStep;
    }

    public void setBarsStep(int barsStep) {
        this.barsStep = barsStep;
    }

    public int getBpm() {
        return bpm;
    }

    public void setBpm(int bpm) {
        this.bpm = bpm;
    }

    public String getGroove() {
        return groove;
    }

    public void setGroove(String groove) {
        this.groove = groove;
    }
}
