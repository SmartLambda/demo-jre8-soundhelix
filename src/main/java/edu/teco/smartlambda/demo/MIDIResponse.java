package edu.teco.smartlambda.demo;

import edu.teco.smartlambda.execution.LambdaReturnValue;

public class MIDIResponse implements LambdaReturnValue {
    private byte[] midi;

    public byte[] getMidi() {
        return midi;
    }

    public void setMidi(final byte[] midi) {
        this.midi = midi;
    }
}
