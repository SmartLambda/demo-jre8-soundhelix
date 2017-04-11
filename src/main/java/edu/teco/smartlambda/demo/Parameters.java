package edu.teco.smartlambda.demo;

import edu.teco.smartlambda.execution.LambdaParameter;

import java.util.Map;

public class Parameters implements LambdaParameter {
    private String arrangement = "percussion";
    private int ticksPerBeat = 12;
    private int maxVelocity = 1000;
    private int bpm = 124;
    private Map<String, Integer> midiChannels;
    private Map<String, Integer> midiPrograms;

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

    public Map<String, Integer> getMidiChannels() {
        return midiChannels;
    }

    public void setMidiChannels(Map<String, Integer> midiChannels) {
        this.midiChannels = midiChannels;
    }

    public Map<String, Integer> getMidiPrograms() {
        return midiPrograms;
    }

    public void setMidiPrograms(Map<String, Integer> midiPrograms) {
        this.midiPrograms = midiPrograms;
    }
}
