package edu.teco.smartlambda.demo;

import edu.teco.smartlambda.execution.LambdaParameter;

public class Parameters implements LambdaParameter {
    private String arrangement = "percussion";
    private int ticksPerBeat = 12;
    private int maxVelocity = 1000;
    private int bpm = 124;

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

    public int getBpm() {
        return bpm;
    }

    public void setBpm(int bpm) {
        this.bpm = bpm;
    }
}
