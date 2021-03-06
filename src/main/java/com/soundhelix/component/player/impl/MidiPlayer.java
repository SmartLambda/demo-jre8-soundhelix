package com.soundhelix.component.player.impl;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.regex.Pattern;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiDevice;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Receiver;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Transmitter;
import javax.xml.xpath.XPathException;

import org.apache.log4j.Logger;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.soundhelix.component.lfo.LFO;
import com.soundhelix.component.lfo.impl.LFOSequenceLFO;
import com.soundhelix.misc.ActivityVector;
import com.soundhelix.misc.Arrangement;
import com.soundhelix.misc.Arrangement.ArrangementEntry;
import com.soundhelix.misc.LFOSequence;
import com.soundhelix.misc.Sequence;
import com.soundhelix.misc.Sequence.SequenceEntry;
import com.soundhelix.misc.SongContext;
import com.soundhelix.misc.Structure;
import com.soundhelix.misc.Track;
import com.soundhelix.misc.Track.TrackType;
import com.soundhelix.util.HarmonyUtils;
import com.soundhelix.util.StringUtils;
import com.soundhelix.util.VersionUtils;
import com.soundhelix.util.XMLUtils;

/**
 * Implements a MIDI player, which can distribute instrument playback to an arbitrary number of MIDI devices in parallel. Each instrument used must be
 * mapped to a combination of MIDI device and MIDI channel. For each channel the MIDI program to use can be defined individually. If no program is
 * specified for a channel, the program is not modified, i.e., the currently selected program is used. All specified MIDI devices are opened for
 * playback, even if they are not used by any instrument. If clock synchronization is enabled for at least one device, the devices are synchronized to
 * the player by sending out TIMING_CLOCK MIDI events to each synchronized device 24 times per beat. For the synchronization to work, each device will
 * be sent a START event before playing and a STOP event after playing. MIDI synchronization works independent of the selected groove. Timing ticks
 * are sent out using a fixed frequency according to the BPM, even though sending out note MIDI messages can vary depending on the selected groove.
 * Clock synchronization should be used for devices using synchronized effects (for example, synchronized echo) in order to communicate the BPM speed
 * to use. As clock synchronization requires some additional overhead, e.g., sending out MIDI messages 24 times per beat instead of the number of
 * ticks per beat, it should only be used if really required.
 * 
 * Timing the ticks (or clock synchronization ticks) is done by using a feedback algorithm based on Thread.sleep() calls with nanosecond resolution.
 * As mentioned, sending out timing ticks is not groove-dependent, whereas sending out note MIDI messages is groove-dependent.
 * 
 * This player supports LFOs, whose frequency can be based on a second, a beat, the whole song or on the activity (first activity until last activity)
 * of an instrument. The granularity of an LFO is always a tick. With every tick, each LFO will send out a MIDI message with the new value for the
 * target controller, but only if the LFO value is the first one sent or if it has changed since the last value sent.
 * 
 * @author Thomas Schuerger (thomas@schuerger.com)
 */

// TODO: add possibility to map a virtual channel to several MIDI channels (do we need this)?
// TODO: on each tick, send all note-offs before sending note-ons (this is currently done per track, but should be done globally)

public class MidiPlayer extends AbstractPlayer {
    /** The maximum time in ms to wait in abortPlay(). */
    private static final int ABORT_PLAY_TIMEOUT = 5000;

    /**
     * The number of MIDI clock synchronization ticks per beat. 24 is the standard MIDI synchronization, 480 is the professional MIDI synchronization.
     */
    private static final int CLOCK_SYNCHRONIZATION_TICKS_PER_BEAT = 24;

    /** The (conservative) pattern for unsafe characters in filenames. */
    private static final Pattern UNSAFE_CHARACTER_PATTERN = Pattern.compile("[^0-9a-zA-Z_\\-]");

    /** The map which maps MIDI controller names to MidiController instances. */
    private static Map<String, MidiController> midiControllerMap;

    /** The logger. */
    protected final Logger logger = Logger.getLogger(this.getClass());

    /** The random generator. */
    private Random random;

    /** The array of MIDI devices. */
    private Device[] devices;

    /** The playback speed in milli-BPM. */
    private int milliBPM;

    /** The transposition pitch. */
    private int transposition;

    /** The array of groove integers. */
    private int[] groove;

    /** The number of ticks to wait before playing. */
    private int beforePlayWaitTicks;

    /** The number of ticks to wait after playing. */
    private int afterPlayWaitTicks;

    /** The commands to execute before starting playing. */
    private String beforePlayCommands;

    /** The commands to execute after stopping playing. */
    private String afterPlayCommands;

    /** The map that maps from channel names to device channels. */
    private Map<String, DeviceChannel> channelMap;

    /** The map that maps from device name to MIDI device. */
    private Map<String, Device> deviceMap;

    /** The device used for receiving MIDI timing ticks. */
    private SyncDevice syncDevice;

    /** The array of initial MIDI controller values. */
    private ControllerValue[] controllerValues;

    /** The array of controller LFOs. */
    private ControllerLFO[] controllerLFOs;

    /** The array of instrument controller LFOs. */
    private InstrumentControllerLFO[] instrumentControllerLFOs;

    /** True if open() has been called, false otherwise. */
    private boolean opened;

    /** True if at least one MIDI device requires clock synchronization. */
    private boolean useClockSynchronization;

    /** True if a request for aborting playback has been received. */
    private boolean playAbortRequested;

    /** True if playback has finished (either because the song has ended or playback has been aborted. */
    private boolean playFinished;

    /** The lock for waiting for playback to finish during abortPlay(). */
    private Object playFinishedLock = new Object();

    /** The minimum window size for MIDI sync BPM calculation. */
    private int minWindowSize;

    /** The maximum window size for MIDI sync BPM calculation. */
    private int maxWindowSize;

    /** The current tick number. */
    private int currentTick;

    /** True if the player is currently skipping to a tick. */
    private boolean skipEnabled;

    /** The tick that the player should skip to (only relevant if skipEnabled is true). */
    private int skipToTick;

    /** Contains the remaining ticks of each note/pause currently played by a voice of an arrangement entry. */
    private List<int[]> tickList;

    /** Contains the pattern position currently played by a voice of an arrangement entry. */
    private List<int[]> posList;

    /** The template for MIDI filenames. */
    private String midiFilename;

    /** The song context (only valid while playing). */
    private SongContext songContext;

    /** If true, then the player will wait before playing until the first BPM calculation has been done. */
    private boolean waitForStart;

    /** If true, then the player is running, otherwise it is stopped. */
    private boolean running;

    /**
     * Contains the pitch used when the last note was played by a voice of an arrangement entry. This is used to be able to change the transposition
     * while playing and still being able to send the correct NOTE_OFF pitches.
     */
    private List<int[]> pitchList;

    static {
        midiControllerMap = new HashMap<String, MidiController>();

        // known controllers

        midiControllerMap.put("pitchBend", new MidiController(ShortMessage.PITCH_BEND, 2));
        midiControllerMap.put("modulationWheel", new MidiController(ShortMessage.CONTROL_CHANGE, 1, 1));
        midiControllerMap.put("breath", new MidiController(ShortMessage.CONTROL_CHANGE, 2, 1));
        midiControllerMap.put("footPedal", new MidiController(ShortMessage.CONTROL_CHANGE, 4, 1));
        midiControllerMap.put("volume", new MidiController(ShortMessage.CONTROL_CHANGE, 7, 1));
        midiControllerMap.put("balance", new MidiController(ShortMessage.CONTROL_CHANGE, 8, 1));
        midiControllerMap.put("undefined9", new MidiController(ShortMessage.CONTROL_CHANGE, 9, 1));
        midiControllerMap.put("pan", new MidiController(ShortMessage.CONTROL_CHANGE, 10, 1));
        midiControllerMap.put("expression", new MidiController(ShortMessage.CONTROL_CHANGE, 11, 1));
        midiControllerMap.put("effect1", new MidiController(ShortMessage.CONTROL_CHANGE, 12, 1));
        midiControllerMap.put("effect2", new MidiController(ShortMessage.CONTROL_CHANGE, 13, 1));
        midiControllerMap.put("variation", new MidiController(ShortMessage.CONTROL_CHANGE, 70, 1));
        midiControllerMap.put("timbre", new MidiController(ShortMessage.CONTROL_CHANGE, 71, 1));
        midiControllerMap.put("releaseTime", new MidiController(ShortMessage.CONTROL_CHANGE, 72, 1));
        midiControllerMap.put("attackTime", new MidiController(ShortMessage.CONTROL_CHANGE, 73, 1));
        midiControllerMap.put("brightness", new MidiController(ShortMessage.CONTROL_CHANGE, 74, 1));
        midiControllerMap.put("controlSoundController10", new MidiController(ShortMessage.CONTROL_CHANGE, 79, 1));

        // generic 7-bit controllers (via controller number)

        for (int i = 0; i < 128; i++) {
            midiControllerMap.put(String.valueOf(i), new MidiController(ShortMessage.CONTROL_CHANGE, i, 1));
        }
    }

    /**
     * Opens all MIDI devices.
     */

    private void open() {
        if (opened) {
            throw new IllegalStateException("open() already called");
        }

        if (syncDevice != null) {
            syncDevice.open();
        }

        try {
            for (Device device : devices) {
                device.open();
            }
        } catch (Exception e) {
            throw new RuntimeException("Could not open MIDI devices", e);
        }

        opened = true;
        playAbortRequested = false;
    }

    /**
     * Closes all MIDI devices.
     */

    private void close() {
        if (devices != null && opened) {
            try {
                muteAllChannels();
            } catch (Exception e) {}

            try {
                for (Device device : devices) {
                    device.close();
                }

                if (syncDevice != null) {
                    syncDevice.close();
                }
            } catch (Exception e) {
                throw new RuntimeException("Could not close MIDI devices");
            }

            devices = null;
            syncDevice = null;
            opened = false;
        }
    }

    /**
     * Sets the MIDI devices.
     * 
     * @param devices the MIDI devices
     */

    private void setDevices(Device[] devices) {
        deviceMap = new HashMap<String, Device>();

        boolean useClockSynchronization = false;

        for (Device device : devices) {
            if (deviceMap.containsKey(device.name)) {
                throw new RuntimeException("Device name \"" + device.name + "\" used more than once");
            }

            deviceMap.put(device.name, device);
            useClockSynchronization |= device.useClockSynchronization;
        }

        this.devices = devices;
        this.useClockSynchronization = useClockSynchronization;
    }

    /**
     * Gets the number of beats per minute for playback.
     * 
     * @return the number of millibeats per minute
     */

    @Override
    public int getMilliBPM() {
        return milliBPM;
    }

    /**
     * Sets the number of beats per minute for playback.
     * 
     * @param milliBPM the number of millibeats per minute
     */

    @Override
    public void setMilliBPM(int milliBPM) {
        if (milliBPM <= 0) {
            throw new IllegalArgumentException("BPM must be > 0");
        }

        this.milliBPM = milliBPM;
    }

    /**
     * Sets the transposition.
     * 
     * @param transposition the transposition pitch
     */

    public void setTransposition(int transposition) {
        if (transposition <= 0) {
            throw new IllegalArgumentException("transposition must be >= 0");
        }

        this.transposition = transposition;
    }

    /**
     * Sets the groove for playback. A groove is a comma-separated list of integers acting as relative weights for tick lengths. The player cycles
     * through this list while playing and uses the list for timing ticks. For example, the string "5,3" results in a ratio of 5:3, namely 5/8 of the
     * total tick length on every even tick and 3/8 of the tick length for every odd tick. If even and odd ticks originally had a length of 100 ms
     * each, then they would be 125 ms and 75 ms, respectively. The default groove (i.e., no groove) is "1", resulting in equally timed ticks. Note
     * that even though the groove is handled correctly by the player, it might not be handled as expected on the MIDI device used for playback. For
     * example, if some time-synchronized echo is used on the MIDI device, it might sound strange if grooved input is used for a non-grooved echo.
     * 
     * @param grooveString the groove string
     */

    public final void setGroove(String grooveString) {
        if (grooveString == null || grooveString.equals("")) {
            grooveString = "1";
        }

        String[] grooveList = grooveString.split(",");
        int len = grooveList.length;

        int sum = 0;

        for (String s : grooveList) {
            sum += Integer.parseInt(s);
        }

        groove = new int[len];
        int totalGroove = 0;

        for (int i = 0; i < len; i++) {
            groove[i] = 1000 * len * Integer.parseInt(grooveList[i]) / sum;
            totalGroove += groove[i];
        }

        // we want a total groove of len*1000
        // totalGroove might be a little off due to rounding
        // errors

        // correct last groove entry, if necessary, to have the
        // correct total groove

        groove[len - 1] -= totalGroove - len * 1000;
    }

    /**
     * Sets the channel map, which maps instruments to MIDI devices and channels. All used instruments must be mapped.
     * 
     * @param channelMap the channel map
     */

    public void setChannelMap(Map<String, DeviceChannel> channelMap) {
        this.channelMap = channelMap;
    }

    /**
     * Tries to find the first available MIDI devices with a MIDI IN or MIDI OUT port among the given MIDI device names.
     * 
     * @param deviceNames the array of MIDI device names
     * @param findMidiIn if true, a MIDI IN device is searched, otherwise a MIDI OUT device is searched
     * 
     * @return a first instantiated MIDI device with MIDI IN or MIDI OUT, or null if none of the devices are available
     */

    private MidiDevice findFirstMidiDevice(String[] deviceNames, boolean findMidiIn) {
        MidiDevice.Info[] infos = MidiSystem.getMidiDeviceInfo();
        Map<String, List<MidiDevice.Info>> map = new HashMap<String, List<MidiDevice.Info>>(infos.length);

        for (MidiDevice.Info info : infos) {
            List<MidiDevice.Info> list = map.get(info.getName());

            if (list == null) {
                list = new ArrayList<MidiDevice.Info>();
                map.put(info.getName(), list);
            }

            list.add(info);
        }

        for (String name : deviceNames) {
            List<MidiDevice.Info> deviceInfos = map.get(name);

            if (deviceInfos != null) {
                for (MidiDevice.Info info : deviceInfos) {
                    // device was found, try to create an instance

                    try {
                        MidiDevice midiDevice = MidiSystem.getMidiDevice(info);

                        if (!findMidiIn && midiDevice.getMaxReceivers() != 0) {
                            return midiDevice;
                        } else if (findMidiIn && midiDevice.getMaxTransmitters() != 0) {
                            return midiDevice;
                        }
                    } catch (Exception e) {
                        logger.debug("MIDI device \"" + name + "\" could not be instantiated", e);
                    }
                }
            } else {
                logger.debug("MIDI device \"" + name + "\" was not found");
            }
        }

        // none of the devices were found or were instantiable
        return null;
    }

    @Override
    public void play(SongContext songContext) {
        if (opened) {
            throw new IllegalStateException("Already playing");
        }

        open();

        try {
            this.songContext = songContext;

            Arrangement arrangement = songContext.getArrangement();

            if (midiFilename != null) {
                saveMidiFiles(this.songContext);
            }

            Structure structure = songContext.getStructure();
            int ticksPerBeat = structure.getTicksPerBeat();
            int ticks = structure.getTicks();

            // when clock synchronization is used, we must make sure that
            // the ticks per beat divide CLOCK_SYNCHRONIZATION_TICKS_PER_BEAT

            if (useClockSynchronization && CLOCK_SYNCHRONIZATION_TICKS_PER_BEAT % ticksPerBeat != 0) {
                throw new RuntimeException("Ticks per beat (" + ticksPerBeat + ") must be a divider of " + CLOCK_SYNCHRONIZATION_TICKS_PER_BEAT
                        + " for MIDI clock synchronization");
            }

            setupMidiDevices();

            int clockTimingsPerTick = useClockSynchronization ? CLOCK_SYNCHRONIZATION_TICKS_PER_BEAT / structure.getTicksPerBeat() : 1;

            if (logger.isInfoEnabled()) {
                logger.info("Song length: " + ticks + " ticks (" + ticks * 60000L / (structure.getTicksPerBeat() * milliBPM) + " seconds @ "
                        + milliBPM / 1000d + " BPM)");
            }

            runBeforePlayCommands();

            long referenceTime = System.nanoTime();
            running = true;

            if (syncDevice != null) {
                if (waitForStart) {
                    logger.info("Waiting for START MIDI message");
                    running = false;

                    while (!running) {
                        referenceTime = waitTicks(referenceTime, 1, clockTimingsPerTick, structure.getTicksPerBeat());
                    }
                }
            }

            sendMidiMessageToClockSynchronized(ShortMessage.START);
            resetPlayerState(arrangement);

            if (beforePlayWaitTicks > 0) {
                if (logger.isInfoEnabled()) {
                    logger.info("Waiting " + beforePlayWaitTicks + " ticks before playing");
                }

                // wait specified number of ticks before starting playing, sending timing ticks if configured
                referenceTime = waitTicks(referenceTime, beforePlayWaitTicks, clockTimingsPerTick, structure.getTicksPerBeat());
            }

            this.currentTick = 0;
            int currentTick = 0;

            long tickReferenceTime = referenceTime;
            long timingTickReferenceTime = useClockSynchronization ? referenceTime : Long.MAX_VALUE;

            // note that we use <= here; this is to make sure that the very last tick is completely processed
            // (including timing ticks); otherwise (with <) the loop would end as soon as the last tick has been
            // played, but the remaining timing ticks for the last tick still need to be processed

            while (currentTick <= ticks && !playAbortRequested) {
                int tick = currentTick;

                while (!running && !playAbortRequested) {
                    // if a STOP event has been received, wait but send timing ticks if configured
                    timingTickReferenceTime = waitTicks(timingTickReferenceTime, 1, clockTimingsPerTick, structure.getTicksPerBeat());
                    tickReferenceTime = timingTickReferenceTime;
                }

                // wait until the next event
                referenceTime = waitNanos(tickReferenceTime, timingTickReferenceTime);

                // in each iteration, at least one of the following two conditions should be true

                if (referenceTime >= timingTickReferenceTime) {
                    if (useClockSynchronization) {
                        sendMidiMessageToClockSynchronized(ShortMessage.TIMING_CLOCK);
                    }

                    timingTickReferenceTime += getTimingTickNanos(clockTimingsPerTick, ticksPerBeat);
                }

                if (referenceTime >= tickReferenceTime) {
                    if (tick == ticks) {
                        break;
                    }

                    playTick(tick);
                    tickReferenceTime += getTickNanos(tick, ticksPerBeat);

                    currentTick++;
                    this.currentTick++;

                    if (skipEnabled) {
                        logger.debug("Skipping to tick " + skipToTick);

                        skipEnabled = false;
                        muteActiveChannels(arrangement);
                        resetPlayerState(arrangement);

                        for (int i = 0; i < skipToTick; i++) {
                            playSilentTick();
                        }

                        this.currentTick = skipToTick;
                        currentTick = skipToTick;
                    }
                }
            }

            logger.trace("Muting all active channels");

            // playing finished, send a NOTE_OFF for all current notes

            muteActiveChannels(arrangement);

            if (afterPlayWaitTicks > 0) {
                if (logger.isInfoEnabled()) {
                    logger.info("Waiting " + afterPlayWaitTicks + " ticks after playing");
                }

                waitTicks(referenceTime, afterPlayWaitTicks, clockTimingsPerTick, structure.getTicksPerBeat());
            }

            runAfterPlayCommands();

            if (useClockSynchronization) {
                sendMidiMessageToClockSynchronized(ShortMessage.STOP);
            }
        } catch (Exception e) {
            throw new RuntimeException("Playback error", e);
        } finally {
            close();

            this.songContext = null;
            this.playFinished = true;

            // notify all threads (if any) that are currently waiting in abortPlay()

            synchronized (playFinishedLock) {
                this.playFinishedLock.notifyAll();
            }
        }
    }

    /**
     * Sets up the MIDI devices.
     * 
     * @throws InvalidMidiDataException in case of invalid MIDI data
     */

    private void setupMidiDevices() throws InvalidMidiDataException {
        initializeControllerLFOs();
        muteAllChannels();
        setChannelPrograms();
        sendControllerValues();
    }

    /**
     * Resets the player state. The state is set up for the first tick.
     * 
     * @param arrangement the arrangement
     */

    private void resetPlayerState(Arrangement arrangement) {
        int arrangements = arrangement.size();

        /** Contains the remaining ticks of each note/pause currently played by a voice of an arrangement entry. */
        tickList = new ArrayList<int[]>(arrangements);

        /** Contains the pattern position currently played by a voice of an arrangement entry. */
        posList = new ArrayList<int[]>(arrangements);

        /**
         * Contains the pitch used when the last note was played by a voice of an arrangement entry. This is used to be able to change the
         * transposition while playing and still being able to send the correct NOTE_OFF pitches.
         */
        pitchList = new ArrayList<int[]>(arrangements);

        for (ArrangementEntry entry : arrangement) {
            int size = entry.getTrack().size();
            tickList.add(new int[size]);
            posList.add(new int[size]);
            pitchList.add(new int[size]);
        }
    }

    /**
     * Runs the configured before play commands (if any).
     * 
     * @throws IOException in case of an I/O problem
     * @throws InterruptedException in case of an interruption
     */
    private void runBeforePlayCommands() throws IOException, InterruptedException {
        if (beforePlayCommands != null && !beforePlayCommands.equals("")) {
            String[] commands = StringUtils.split(beforePlayCommands, ';');

            for (String command : commands) {
                String replacedCommand = replacePlaceholders(command);
                logger.debug("Running \"" + replacedCommand + "\"");
                Process process = Runtime.getRuntime().exec(replacedCommand);
                int rc = process.waitFor();

                if (rc != 0) {
                    throw new RuntimeException("Command \"" + replacedCommand + "\" exited with non-zero exit code " + rc);
                }
            }
        }
    }

    /**
     * Runs the configured after play commands (if any).
     * 
     * @throws IOException in case of an I/O problem
     * @throws InterruptedException in case of an interruption
     */
    private void runAfterPlayCommands() throws IOException, InterruptedException {
        if (afterPlayCommands != null && !afterPlayCommands.equals("")) {
            String[] commands = StringUtils.split(afterPlayCommands, ';');

            for (String command : commands) {
                String replacedCommand = replacePlaceholders(command);
                logger.debug("Running \"" + replacedCommand + "\"");
                Process process = Runtime.getRuntime().exec(replacedCommand);
                int rc = process.waitFor();

                if (rc != 0) {
                    throw new RuntimeException("Command \"" + replacedCommand + "\" exited with non-zero exit code " + rc);
                }
            }
        }
    }

    /**
     * Mutes all active channels.
     * 
     * @param arrangement the arrangement
     * 
     * @throws InvalidMidiDataException in case of invalid MIDI data
     */

    private void muteActiveChannels(Arrangement arrangement) throws InvalidMidiDataException {
        int k = 0;

        for (ArrangementEntry entry : arrangement) {
            Track track = entry.getTrack();
            String instrument = entry.getInstrument();

            DeviceChannel channel = channelMap.get(instrument);

            if (channel == null) {
                throw new RuntimeException("Instrument " + instrument + " not mapped to MIDI device/channel combination");
            }

            int[] p = posList.get(k);
            int[] pitch = pitchList.get(k);

            for (int j = 0; j < p.length; j++) {
                Sequence s = track.get(j);

                if (p[j] > 0 && s.get(p[j] - 1).isNote()) {
                    sendMidiMessage(channel, ShortMessage.NOTE_OFF, pitch[j], 0);
                }
            }

            k++;
        }
    }

    /**
     * Saves the arrangement as one or more MIDI files.
     * 
     * @throws InvalidMidiDataException in case of a MIDI data problem
     * @throws IOException in case of an I/O problem
     */

    public void saveMidiFiles(SongContext songContext) throws InvalidMidiDataException, IOException {
        this.songContext = songContext;
        Arrangement arrangement = songContext.getArrangement();

        initializeControllerLFOs();

        Structure structure = songContext.getStructure();
        int ticksPerBeat = structure.getTicksPerBeat();
        int ticks = structure.getTicks();

        Map<Device, javax.sound.midi.Sequence> sequenceMap = new HashMap<Device, javax.sound.midi.Sequence>();
        Map<Device, javax.sound.midi.Track> trackMap = new HashMap<Device, javax.sound.midi.Track>();

        for (Device device : devices) {
            javax.sound.midi.Sequence s = new javax.sound.midi.Sequence(javax.sound.midi.Sequence.PPQ, ticksPerBeat);
            sequenceMap.put(device, s);
            javax.sound.midi.Track metaTrack = s.createTrack();
            trackMap.put(device, s.createTrack());

            long mpqn = 60000000000L / milliBPM;

            MetaMessage mt = new MetaMessage();
            byte[] bt = {(byte) (mpqn / 65536 & 0xFF), (byte) (mpqn / 256 & 0xFF), (byte) (mpqn & 0xFF)};
            mt.setMessage(0x51, bt, 3);
            metaTrack.add(new MidiEvent(mt, 0L));

            mt = new MetaMessage();
            bt = songContext.getSongName().getBytes("ISO-8859-1");
            mt.setMessage(0x01, bt, bt.length);
            metaTrack.add(new MidiEvent(mt, 0L));

            mt = new MetaMessage();
            bt = ("Created with " + VersionUtils.getVersion()).getBytes("ISO-8859-1");
            mt.setMessage(0x02, bt, bt.length);
            metaTrack.add(new MidiEvent(mt, 0L));
        }

        Map<DeviceChannel, Boolean> map = new HashMap<DeviceChannel, Boolean>();

        for (DeviceChannel dc : channelMap.values()) {
            if (dc.program != -1 && !map.containsKey(dc)) {
                sendMidiMessage(trackMap.get(dc.device), 0, dc.channel, ShortMessage.PROGRAM_CHANGE, dc.program, 0);
                map.put(dc, true);
            }
        }

        for (ControllerValue cvalue : controllerValues) {
            String controller = cvalue.controller;
            Device device = deviceMap.get(cvalue.deviceName);
            int value = cvalue.value;

            MidiController midiController = midiControllerMap.get(controller);

            if (midiController != null) {
                if (midiController.parameter == -1 && midiController.byteCount == 2) {
                    sendMidiMessage(trackMap.get(device), 0, cvalue.channel, midiController.status, value % 128, value / 128);
                } else if (midiController.parameter >= 0 && midiController.byteCount == 1) {
                    sendMidiMessage(trackMap.get(device), 0, cvalue.channel, midiController.status, midiController.parameter, value);
                } else {
                    throw new RuntimeException("Error in MIDI controller \"" + controller + "\"");
                }
            } else {
                throw new RuntimeException("Invalid MIDI controller \"" + controller + "\"");
            }
        }

        resetPlayerState(arrangement);

        // contains a list of all current pitches where a NOTE_OFF must be sent after the next NOTE_ON (usually tiny)
        List<Integer> legatoList = new ArrayList<Integer>();

        int tick = 0;

        // remember transposition value so that a parallel change of the global value has no effect on this tick
        int transposition = this.transposition;

        while (tick < ticks) {
            sendControllerLFOMessages(trackMap, tick);

            int k = 0;

            for (ArrangementEntry entry : arrangement) {
                Track track = entry.getTrack();
                String instrument = entry.getInstrument();

                DeviceChannel channel = channelMap.get(instrument);

                if (channel == null) {
                    throw new RuntimeException("Instrument " + instrument + " not mapped to MIDI device/channel combination");
                }

                int[] t = tickList.get(k);
                int[] p = posList.get(k);
                int[] pitches = pitchList.get(k);

                legatoList.clear();

                // send all NOTE_OFFs where no legato is used and remember legato pitches

                for (int j = 0; j < t.length; j++) {

                    if (--t[j] <= 0) {
                        Sequence s = track.get(j);

                        if (p[j] > 0) {
                            SequenceEntry prevse = s.get(p[j] - 1);
                            if (prevse.isNote()) {
                                int pitch = pitches[j];

                                // use legato iff the previous note has the legato flag set and has a different
                                // pitch than the current note (legato from a pitch to the same pitch is not possible)

                                if (!prevse.isLegato() || prevse.getPitch() == s.get(p[j]).getPitch()) {
                                    // legato flag is inactive or the pitch of the previous note is the same
                                    sendMidiMessage(trackMap.get(channel.device), tick, channel.channel, ShortMessage.NOTE_OFF, pitch, 0);
                                } else {
                                    // valid legato case
                                    // remember pitch for NOTE_OFF after the next NOTE_ON
                                    legatoList.add(pitch);
                                }
                            }
                        }
                    }
                }

                // send all NOTE_ONs

                for (int j = 0; j < t.length; j++) {
                    if (t[j] <= 0) {
                        try {
                            Sequence s = track.get(j);
                            SequenceEntry se = s.get(p[j]);

                            if (se.isNote()) {
                                int pitch = (track.getType() == TrackType.MELODIC ? transposition : 0) + se.getPitch();
                                sendMidiMessage(trackMap.get(channel.device), tick, channel.channel, ShortMessage.NOTE_ON, pitch, getMidiVelocity(
                                        songContext, se.getVelocity()));
                                pitches[j] = pitch;
                            }

                            p[j]++;
                            t[j] = se.getTicks();
                        } catch (Exception e) {
                            throw new RuntimeException("Error at k=" + k + "  j=" + j + "  p[j]=" + p[j], e);
                        }
                    }
                }

                // send NOTE_OFFs for all pitches on the legato list

                for (int pitch : legatoList) {
                    sendMidiMessage(trackMap.get(channel.device), tick, channel.channel, ShortMessage.NOTE_OFF, pitch, 0);
                }

                k++;
            }

            tick++;
        }

        int number = 1;

        for (Device device : devices) {
            Map<String, String> auxMap = new HashMap<String, String>();
            auxMap.put("deviceName", device.name);
            auxMap.put("deviceNumber", String.valueOf(number));
            String midiFilename = replacePlaceholders(this.midiFilename, auxMap);

            File file = new File(midiFilename);
            File directory = file.getParentFile();

            if (directory != null && !directory.exists()) {
                logger.info("Creating MIDI file directory \"" + directory.getAbsolutePath() + "\"");
                directory.mkdirs();
            }

            MidiSystem.write(sequenceMap.get(device), 1, file);
            logger.debug("Wrote MIDI data for device \"" + device.name + "\" to MIDI file \"" + midiFilename + "\" (" + file.length() + " bytes)");
            number++;
        }
    }

    /**
     * Plays a tick, sending NOTE_OFF messages for notes that should be muted and NOTE_ON messages for notes that should be started.
     * 
     * @param tick the tick
     * 
     * @throws InvalidMidiDataException in case of invalid MIDI data
     */

    private void playTick(int tick) throws InvalidMidiDataException {
        Arrangement arrangement = songContext.getArrangement();
        Structure structure = songContext.getStructure();

        if (tick == 0 || songContext.getHarmony().getChordSectionTicks(tick - 1) == 1) {
            logger.info(String.format("Tick: %5d   Chord section: %3d   Seconds: %4d   Progress: %5.1f %%", tick, HarmonyUtils.getChordSectionNumber(
                    songContext, tick), tick * 60 * 1000 / (structure.getTicksPerBeat() * milliBPM), (double) tick * 100 / structure.getTicks()));
        }

        sendControllerLFOMessages(tick);

        // contains a list of all current pitches where a NOTE_OFF must be sent after the next NOTE_ON (usually tiny)
        List<Integer> legatoList = new ArrayList<Integer>();

        int k = 0;

        // remember transposition value so that a parallel change of the global value has no effect on this tick
        int transposition = this.transposition;

        for (ArrangementEntry entry : arrangement) {
            Track track = entry.getTrack();
            String instrument = entry.getInstrument();

            DeviceChannel channel = channelMap.get(instrument);

            if (channel == null) {
                throw new RuntimeException("Instrument " + instrument + " not mapped to MIDI device/channel combination");
            }

            int[] t = tickList.get(k);
            int[] p = posList.get(k);
            int[] pitches = pitchList.get(k);

            legatoList.clear();

            // send all NOTE_OFFs where no legato is used and remember legato pitches

            for (int j = 0; j < t.length; j++) {

                if (--t[j] <= 0) {
                    Sequence s = track.get(j);

                    if (p[j] > 0) {
                        SequenceEntry prevse = s.get(p[j] - 1);
                        if (prevse.isNote()) {
                            int pitch = pitches[j];

                            // use legato iff the previous note has the legato flag set and has a different
                            // pitch than the current note (legato from a pitch to the same pitch is not possible)

                            if (!prevse.isLegato() || prevse.getPitch() == s.get(p[j]).getPitch()) {
                                // legato flag is inactive or the pitch of the previous note is the same
                                sendMidiMessage(channel, ShortMessage.NOTE_OFF, pitch, 0);
                            } else {
                                // valid legato case
                                // remember pitch for NOTE_OFF after the next NOTE_ON
                                legatoList.add(pitch);
                            }
                        }
                    }
                }
            }

            // send all NOTE_ONs

            for (int j = 0; j < t.length; j++) {
                if (t[j] <= 0) {
                    try {
                        Sequence s = track.get(j);
                        SequenceEntry se = s.get(p[j]);

                        if (se.isNote()) {
                            int pitch = (track.getType() == TrackType.MELODIC ? transposition : 0) + se.getPitch();
                            sendMidiMessage(channel, ShortMessage.NOTE_ON, pitch, getMidiVelocity(songContext, se.getVelocity()));
                            pitches[j] = pitch;
                        }

                        p[j]++;
                        t[j] = se.getTicks();
                    } catch (Exception e) {
                        throw new RuntimeException("Error at k=" + k + "  j=" + j + "  p[j]=" + p[j], e);
                    }
                }
            }

            // send NOTE_OFFs for all pitches on the legato list

            for (int pitch : legatoList) {
                sendMidiMessage(channel, ShortMessage.NOTE_OFF, pitch, 0);
            }

            k++;
        }
    }

    /**
     * Updates the player state to simulate playing the next tick.
     */

    private void playSilentTick() {
        int k = 0;

        for (ArrangementEntry entry : songContext.getArrangement()) {
            Track track = entry.getTrack();

            int[] t = tickList.get(k);
            int[] p = posList.get(k);
            int[] pitches = pitchList.get(k);

            for (int j = 0; j < t.length; j++) {
                if (--t[j] <= 0) {
                    Sequence s = track.get(j);
                    SequenceEntry se = s.get(p[j]);

                    if (se.isNote()) {
                        int pitch = (track.getType() == TrackType.MELODIC ? transposition : 0) + se.getPitch();
                        pitches[j] = pitch;
                    }

                    p[j]++;
                    t[j] = se.getTicks();
                }
            }

            k++;
        }
    }

    /**
     * Initializes all controller LFOs of the arrangement.
     */

    private void initializeControllerLFOs() {
        Structure structure = songContext.getStructure();
        Arrangement arrangement = songContext.getArrangement();

        for (ControllerLFO clfo : controllerLFOs) {
            if (clfo.rotationUnit.equals("song")) {
                clfo.lfo.setPhase(clfo.phase);
                clfo.lfo.setSongSpeed(clfo.speed, structure.getTicks());
            } else if (clfo.rotationUnit.equals("activity")) {
                // if the instrument is inactive or not part of the song, we
                // use the whole song as the length (this LFO is then a no-op)

                int[] ticks;

                if (clfo.instrument != null) {
                    ticks = getInstrumentActivity(arrangement, clfo.instrument);
                } else {
                    ticks = getActivityVectorActivity(clfo.activityVector);
                }

                int startTick = 0;
                int endTick = structure.getTicks();

                if (ticks != null) {
                    startTick = ticks[0];
                    endTick = ticks[1];

                    if (startTick >= endTick) {
                        // track belonging to instrument is silent all the time
                        startTick = 0;
                        endTick = structure.getTicks();
                    }
                }

                clfo.lfo.setPhase(clfo.phase);
                clfo.lfo.setActivitySpeed(clfo.speed, startTick, endTick);
            } else if (clfo.rotationUnit.equals("segmentPair")) {
                clfo.lfo.setPhase(clfo.phase);
                ActivityVector av = songContext.getActivityMatrix().get(clfo.activityVector);

                if (av == null) {
                    throw new RuntimeException("ActivityVector \"" + clfo.activityVector + "\" for LFO not found");
                }

                clfo.lfo.setSegmentPairSpeed(clfo.speed, av);
            } else if (clfo.rotationUnit.equals("beat")) {
                clfo.lfo.setPhase(clfo.phase);
                clfo.lfo.setBeatSpeed(clfo.speed, structure.getTicksPerBeat());
            } else if (clfo.rotationUnit.equals("second")) {
                clfo.lfo.setPhase(clfo.phase);
                clfo.lfo.setTimeSpeed(clfo.speed, structure.getTicksPerBeat(), milliBPM / 1000.0d);
            } else {
                throw new RuntimeException("Invalid rotation unit \"" + clfo.rotationUnit + "\"");
            }
        }

        for (InstrumentControllerLFO clfo : instrumentControllerLFOs) {
            String instrument = clfo.instrument;
            Track track = songContext.getArrangement().get(instrument).getTrack();

            if (track == null) {
                throw new RuntimeException("Unvalid instrument \"" + instrument + "\" for instrument controller LFO");
            }

            String lfoName = clfo.lfoName;
            LFOSequence lfoSequence = track.getLFOSequence(lfoName);

            if (lfoSequence == null) {
                throw new RuntimeException("Unvalid LFO \"" + lfoName + "\" for instrument \"" + instrument + "\" for instrument controller LFO");
            }

            LFO lfo = new LFOSequenceLFO(lfoSequence);
            lfo.setMinAmplitude(clfo.minAmplitude);
            lfo.setMaxAmplitude(clfo.maxAmplitude);
            lfo.setMinValue(clfo.minValue);
            lfo.setMaxValue(clfo.maxValue);
            clfo.lfo = lfo;
        }
    }

    /**
     * Returns an array consisting of the first activity tick (inclusive) and the last activity tick (exclusive). If the ActivityVector is never
     * active, null is returned.
     * 
     * @param activityVectorName the name of the ActivityVector
     * @return the array (or null)
     */

    private int[] getActivityVectorActivity(String activityVectorName) {
        ActivityVector av = songContext.getActivityMatrix().get(activityVectorName);

        if (av == null) {
            throw new RuntimeException("ActivityVector \"" + activityVectorName + "\" for LFO not found");
        }

        int[] ticks;

        int start = av.getFirstActiveTick();
        int end = av.getLastActiveTick() + 1;

        if (start >= 0) {
            ticks = new int[] {start, end};
        } else {
            ticks = null;
        }

        return ticks;
    }

    /**
     * Sends messages to all configured controllers based on the LFOs. A message is only send to a controller if its LFO value has changed or if tick
     * is 0.
     * 
     * @param tick the tick
     * 
     * @throws InvalidMidiDataException in case of invalid MIDI data
     */

    private void sendControllerLFOMessages(int tick) throws InvalidMidiDataException {
        // handle controller LFOs

        for (ControllerLFO clfo : controllerLFOs) {
            int value = clfo.lfo.getTickValue(tick);

            if (tick == 0 || value != clfo.lastSentValue) {
                // value has changed or is the first value, send message

                String controller = clfo.controller;
                Device device = deviceMap.get(clfo.deviceName);

                if (controller.equals("milliBPM")) {
                    this.milliBPM = value;
                } else {
                    MidiController midiController = midiControllerMap.get(controller);

                    if (midiController != null) {
                        if (midiController.parameter == -1 && midiController.byteCount == 2) {
                            sendMidiMessage(device, clfo.channel, midiController.status, value % 128, value / 128);
                        } else if (midiController.parameter >= 0 && midiController.byteCount == 1) {
                            sendMidiMessage(device, clfo.channel, midiController.status, midiController.parameter, value);
                        } else {
                            throw new RuntimeException("Error in LFO MIDI controller \"" + controller + "\"");
                        }
                    } else {
                        throw new RuntimeException("Invalid LFO MIDI controller \"" + controller + "\"");
                    }
                }

                clfo.lastSentValue = value;
            }
        }

        // handle instrument controller LFOs

        for (InstrumentControllerLFO clfo : instrumentControllerLFOs) {
            int value = clfo.lfo.getTickValue(tick);

            if (tick == 0 || value != clfo.lastSentValue) {
                // value has changed or is the first value, send message

                String controller = clfo.controller;
                Device device = deviceMap.get(clfo.deviceName);

                if (controller.equals("milliBPM")) {
                    this.milliBPM = value;
                } else {
                    MidiController midiController = midiControllerMap.get(controller);

                    if (midiController != null) {
                        if (midiController.parameter == -1 && midiController.byteCount == 2) {
                            sendMidiMessage(device, clfo.channel, midiController.status, value % 128, value / 128);
                        } else if (midiController.parameter >= 0 && midiController.byteCount == 1) {
                            sendMidiMessage(device, clfo.channel, midiController.status, midiController.parameter, value);
                        } else {
                            throw new RuntimeException("Error in LFO MIDI controller \"" + controller + "\"");
                        }
                    } else {
                        throw new RuntimeException("Invalid LFO MIDI controller \"" + controller + "\"");
                    }
                }

                clfo.lastSentValue = value;
            }
        }
    }

    /**
     * Sends messages to all configured controllers based on the LFOs. A message is only send to a controller if its LFO value has changed or if tick
     * is 0.
     * 
     * @param trackMap the map that maps devices to MIDI tracks
     * @param tick the tick
     * 
     * @throws InvalidMidiDataException in case of invalid MIDI data
     */

    private void sendControllerLFOMessages(Map<Device, javax.sound.midi.Track> trackMap, int tick) throws InvalidMidiDataException {
        for (ControllerLFO clfo : controllerLFOs) {
            int value = clfo.lfo.getTickValue(tick);

            if (tick == 0 || value != clfo.lastSentValue) {
                // value has changed or is the first value, send message

                String controller = clfo.controller;
                Device device = deviceMap.get(clfo.deviceName);
                javax.sound.midi.Track track = trackMap.get(device);

                if (controller.equals("milliBPM")) {
                    long mpqn = 60000000000L / value;
                    MetaMessage mt = new MetaMessage();
                    byte[] bt = {(byte) (mpqn / 65536 & 0xFF), (byte) (mpqn / 256 & 0xFF), (byte) (mpqn & 0xFF)};
                    mt.setMessage(0x51, bt, 3);

                    for (Device d : devices) {
                        trackMap.get(d).add(new MidiEvent(mt, (long) tick + 1));
                    }
                } else {
                    MidiController midiController = midiControllerMap.get(controller);

                    if (midiController != null) {
                        if (midiController.parameter == -1 && midiController.byteCount == 2) {
                            sendMidiMessage(track, tick, clfo.channel, midiController.status, value % 128, value / 128);
                        } else if (midiController.parameter >= 0 && midiController.byteCount == 1) {
                            sendMidiMessage(track, tick, clfo.channel, midiController.status, midiController.parameter, value);
                        } else {
                            throw new RuntimeException("Error in LFO MIDI controller \"" + controller + "\"");
                        }
                    } else {
                        throw new RuntimeException("Invalid LFO MIDI controller \"" + controller + "\"");
                    }
                }

                clfo.lastSentValue = value;
            }
        }

        for (InstrumentControllerLFO clfo : instrumentControllerLFOs) {
            int value = clfo.lfo.getTickValue(tick);

            if (tick == 0 || value != clfo.lastSentValue) {
                // value has changed or is the first value, send message

                String controller = clfo.controller;
                Device device = deviceMap.get(clfo.deviceName);
                javax.sound.midi.Track track = trackMap.get(device);

                if (controller.equals("milliBPM")) {
                    long mpqn = 60000000000L / value;
                    MetaMessage mt = new MetaMessage();
                    byte[] bt = {(byte) (mpqn / 65536 & 0xFF), (byte) (mpqn / 256 & 0xFF), (byte) (mpqn & 0xFF)};
                    mt.setMessage(0x51, bt, 3);

                    for (Device d : devices) {
                        trackMap.get(d).add(new MidiEvent(mt, (long) tick + 1));
                    }
                } else {
                    MidiController midiController = midiControllerMap.get(controller);

                    if (midiController != null) {
                        if (midiController.parameter == -1 && midiController.byteCount == 2) {
                            sendMidiMessage(track, tick, clfo.channel, midiController.status, value % 128, value / 128);
                        } else if (midiController.parameter >= 0 && midiController.byteCount == 1) {
                            sendMidiMessage(track, tick, clfo.channel, midiController.status, midiController.parameter, value);
                        } else {
                            throw new RuntimeException("Error in LFO MIDI controller \"" + controller + "\"");
                        }
                    } else {
                        throw new RuntimeException("Invalid LFO MIDI controller \"" + controller + "\"");
                    }
                }

                clfo.lastSentValue = value;
            }
        }

    }

    /**
     * Waits the given number of ticks, sending out TIMING_CLOCK events to the MIDI devices, if necessary. Waiting is done by using a simple feedback
     * algorithm that tries hard to keep the player exactly in sync with the system clock.
     * 
     * @param referenceTime the reference time (from System.nanoTime())
     * @param ticks the number of ticks to wait
     * @param clockTimingsPerTick the number of clock timings per tick
     * @param ticksPerBeat the number of ticks per beat
     * 
     * @return the new reference time
     * 
     * @throws InvalidMidiDataException in case of invalid MIDI data
     * @throws InterruptedException in case of sleep interruption
     */

    private long waitTicks(long referenceTime, int ticks, int clockTimingsPerTick, int ticksPerBeat) throws InvalidMidiDataException,
            InterruptedException {
        long lastWantedNanos = referenceTime;

        for (int t = 0; t < ticks && !playAbortRequested && !skipEnabled; t++) {
            for (int s = 0; s < clockTimingsPerTick; s++) {

                long length = getTimingTickNanos(clockTimingsPerTick, ticksPerBeat);

                long wantedNanos = lastWantedNanos + length;
                long wait = Math.max(0, wantedNanos - System.nanoTime());

                if (wait > 0) {
                    Thread.sleep((int) (wait / 1000000L), (int) (wait % 1000000L));
                }

                if (useClockSynchronization) {
                    sendMidiMessageToClockSynchronized(ShortMessage.TIMING_CLOCK);
                }

                lastWantedNanos = wantedNanos;
            }
        }

        return lastWantedNanos;
    }

    /**
     * Waits until either time1 or time2 (both are given in nano seconds) is reached, whichever comes first. Both times are based on
     * System.nanoTime(). If time1 or time2 is in the past, this method returns immediately. In all cases, the time waited on is returned (either
     * time1 or time2).
     * 
     * @param time1 the first point in time
     * @param time2 the second point in time
     * 
     * @return the point in time waited on (minimum of time1 and time2)
     * 
     * @throws InterruptedException in case of sleep interruption
     */

    private long waitNanos(long time1, long time2) throws InterruptedException {
        long wantedNanos = Math.min(time1, time2);
        long wait = Math.max(0, wantedNanos - System.nanoTime());

        if (wait > 0) {
            Thread.sleep((int) (wait / 1000000L), (int) (wait % 1000000L));
        }

        return wantedNanos;
    }

    /**
     * Returns the number of nanos of the given tick, taking the current groove into account.
     * 
     * @param tick the tick
     * @param ticksPerBeat the number of ticks per beat
     * 
     * @return the number of nanos
     */

    private long getTickNanos(int tick, int ticksPerBeat) {
        return 60000000000L * groove[tick % groove.length] / ((long) ticksPerBeat * milliBPM);
    }

    /**
     * Returns the number of nanos for a timing tick.
     * 
     * @param ticksPerBeat the ticks per beat
     * @param clockTimingsPerTick the clock timings per tick
     * 
     * @return the number of nanos for a timing tick
     */

    private long getTimingTickNanos(int ticksPerBeat, int clockTimingsPerTick) {
        return 60000000000000L / ((long) ticksPerBeat * milliBPM * clockTimingsPerTick);
    }

    /**
     * Sets the channel programs of all DeviceChannels used. This method does not set the program of a DeviceChannel more than once. Channels whose
     * program is set to -1 are ignored, so that the currently selected program remains active.
     * 
     * @throws InvalidMidiDataException in case of invalid MIDI data
     */

    private void setChannelPrograms() throws InvalidMidiDataException {
        // we use a Map to track whether a program has been set already

        Map<DeviceChannel, Boolean> map = new HashMap<DeviceChannel, Boolean>();

        for (DeviceChannel dc : channelMap.values()) {
            if (dc.program != -1 && !map.containsKey(dc)) {
                sendMidiMessage(dc, ShortMessage.PROGRAM_CHANGE, dc.program, 0);
                map.put(dc, true);
            }
        }
    }

    /**
     * Sends the initial MIDI controller values.
     * 
     * @throws InvalidMidiDataException in case of invalid MIDI data
     */

    private void sendControllerValues() throws InvalidMidiDataException {
        for (ControllerValue cvalue : controllerValues) {
            String controller = cvalue.controller;
            Device device = deviceMap.get(cvalue.deviceName);
            int value = cvalue.value;

            MidiController midiController = midiControllerMap.get(controller);

            if (midiController != null) {
                if (midiController.parameter == -1 && midiController.byteCount == 2) {
                    sendMidiMessage(device, cvalue.channel, midiController.status, value % 128, value / 128);
                } else if (midiController.parameter >= 0 && midiController.byteCount == 1) {
                    sendMidiMessage(device, cvalue.channel, midiController.status, midiController.parameter, value);
                } else {
                    throw new RuntimeException("Error in MIDI controller \"" + controller + "\"");
                }
            } else {
                throw new RuntimeException("Invalid MIDI controller \"" + controller + "\"");
            }
        }
    }

    /**
     * Sends the given single-byte message to all devices that are using clock synchronization.
     * 
     * @param status the message
     * 
     * @throws InvalidMidiDataException in case of invalid MIDI data
     */

    private void sendMidiMessageToClockSynchronized(int status) throws InvalidMidiDataException {
        if (useClockSynchronization) {
            for (Device device : deviceMap.values()) {
                if (device.useClockSynchronization) {
                    sendMidiMessage(device, status);
                }
            }
        }
    }

    /**
     * Mutes all channels of all devices. This is done by sending an ALL SOUND OFF message to all channels. In addition to that (because this does not
     * include sending NOTE OFF) a NOTE_OFF is sent for each of the 128 possible pitches to each channel.
     * 
     * @throws InvalidMidiDataException in case of invalid MIDI data
     */

    public final void muteAllChannels() throws InvalidMidiDataException {
        if (!opened) {
            // this method can be called externally; ignore call if not open
            return;
        }

        for (DeviceChannel dc : channelMap.values()) {
            logger.trace("Muting channel " + dc);

            // send ALL SOUND OFF message
            sendMidiMessage(dc, ShortMessage.CONTROL_CHANGE, 120, 0);

            // send ALL NOTES OFF message (doesn't work on all MIDI devices)
            sendMidiMessage(dc, ShortMessage.CONTROL_CHANGE, 123, 0);

            for (int i = 0; i < 128; i++) {
                sendMidiMessage(dc, ShortMessage.NOTE_OFF, i, 0);
            }
        }
    }

    /**
     * Converts our internal velocity (between 0 and maxVelocity) to a MIDI velocity (between 0 and 127).
     * 
     * @param songContext the song context
     * @param velocity the velocity to convert
     * 
     * @return the MIDI velocity
     */

    private static int getMidiVelocity(SongContext songContext, int velocity) {
        if (velocity == 0) {
            return 0;
        }

        return 1 + (int) ((velocity - 1) * 126L / (songContext.getStructure().getMaxVelocity() - 1));
    }

    public final void setControllerLFOs(ControllerLFO[] controllerLFOs) {
        this.controllerLFOs = controllerLFOs;
    }

    public final void setInstrumentControllerLFOs(InstrumentControllerLFO[] instrumentControllerLFOs) {
        this.instrumentControllerLFOs = instrumentControllerLFOs;
    }

    /**
     * Checks if the given instrument is part of the arrangement and if so, determines the tick of the first note and the tick of the end of the last
     * note plus 1. All sequences of the instrument are checked, and the minimum and maximum accross all sequences are determined. The start and end
     * ticks are returned as a two-element int array. If the instrument is not found or the instrument's track contains no note, null is returned.
     * 
     * @param arrangement the arrangement
     * @param instrument the number of the instrument
     * 
     * @return a two-element int array containing start and end tick (or null)
     */

    private static int[] getInstrumentActivity(Arrangement arrangement, String instrument) {
        for (ArrangementEntry entry : arrangement) {
            if (entry.getInstrument().equals(instrument)) {
                // instrument found, check for first and last tick

                Track track = entry.getTrack();

                int startTick = Integer.MAX_VALUE;
                int endTick = Integer.MIN_VALUE;

                for (int k = 0; k < track.size(); k++) {
                    Sequence seq = track.get(k);
                    int ticks = seq.getTicks();

                    int tick = 0;
                    int j = 0;

                    while (tick < ticks) {
                        SequenceEntry se = seq.get(j++);

                        if (se.isNote()) {
                            if (tick < startTick) {
                                startTick = tick;
                            }
                            if (tick + se.getTicks() > endTick) {
                                endTick = tick + se.getTicks();
                            }
                        }

                        tick += se.getTicks();
                    }
                }

                if (startTick == Integer.MAX_VALUE) {
                    // instrument was present but completely silent
                    return null;
                } else {
                    // both startTick and endTick contain a proper value
                    return new int[] {startTick, endTick};
                }
            }
        }

        // instrument was not found
        return null;
    }

    /**
     * Takes the given string and replaces all valid placeholders with their values.
     * 
     * @param string the string
     * 
     * @return the string with replaced placeholders
     */

    private String replacePlaceholders(String string) {
        return replacePlaceholders(string, null);
    }

    /**
     * Takes the given string and replaces all valid placeholders with their values. Additional placeholders can be provided in the auxiliary map.
     * 
     * @param string the string
     * @param auxMap the auxiliary map
     * 
     * @return the string with replaced placeholders
     */

    private String replacePlaceholders(String string, Map<String, String> auxMap) {
        String songName = songContext.getSongName();

        string = string.replace("${songName}", songName);
        string = string.replace("${safeSongName}", UNSAFE_CHARACTER_PATTERN.matcher(songName).replaceAll("_"));
        string = string.replace("${randomSeed}", String.valueOf(songContext.getRandomSeed()));
        string = string.replace("${safeRandomSeed}", String.valueOf(songContext.getRandomSeed()));

        if (auxMap != null) {
            for (Map.Entry<String, String> entry : auxMap.entrySet()) {
                string = string.replace("${" + entry.getKey() + "}", entry.getValue());
                string = string.replace("${safe" + Character.toUpperCase(entry.getKey().charAt(0)) + entry.getKey().substring(1) + "}",
                        UNSAFE_CHARACTER_PATTERN.matcher(entry.getValue()).replaceAll("_"));
            }
        }

        return string;
    }

    @Override
    public final void configure(SongContext songContext, Node node) throws XPathException {
        random = new Random(randomSeed);

        setMidiFilename(XMLUtils.parseString(random, XMLUtils.getNode("midiFilename", node)));
        setMidiFilename(XMLUtils.parseString(random, "midiFilename", node));

        NodeList nodeList = XMLUtils.getNodeList("device", node);
        int entries = nodeList.getLength();
        Device[] devices = new Device[entries];

        for (int i = 0; i < entries; i++) {
            String name = XMLUtils.parseString(random, "@name", nodeList.item(i));
            String midiName = XMLUtils.parseString(random, nodeList.item(i));
            boolean useClockSynchronization = XMLUtils.parseBoolean(random, "@clockSynchronization", nodeList.item(i));
            devices[i] = new Device(name, midiName, useClockSynchronization);
        }

        SyncDevice syncDevice = null;

        try {
            String syncDeviceName = XMLUtils.parseString(random, "synchronizationDevice", node);
            syncDevice = new SyncDevice(syncDeviceName);
            this.syncDevice = syncDevice;
        } catch (Exception e) {}

        boolean waitForStart = true;

        try {
            waitForStart = XMLUtils.parseBoolean(random, "synchronizationDevice/@waitForStart", node);
        } catch (Exception e) {}

        int minWindowSize = 24;

        try {
            minWindowSize = XMLUtils.parseInteger(random, "synchronizationDevice/@minWindowSize", node);
        } catch (Exception e) {}

        int maxWindowSize = 24;

        try {
            maxWindowSize = XMLUtils.parseInteger(random, "synchronizationDevice/@maxWindowSize", node);
        } catch (Exception e) {}

        setWaitForStart(waitForStart);
        setMinWindowSize(minWindowSize);
        setMaxWindowSize(maxWindowSize);

        beforePlayCommands = XMLUtils.parseString(random, "beforePlayCommands", node);
        afterPlayCommands = XMLUtils.parseString(random, "afterPlayCommands", node);

        setDevices(devices);
        setMilliBPM(1000 * XMLUtils.parseInteger(random, "bpm", node));
        setTransposition(XMLUtils.parseInteger(random, "transposition", node));
        setGroove(XMLUtils.parseString(random, "groove", node));
        setBeforePlayWaitTicks(XMLUtils.parseInteger(random, "beforePlayWaitTicks", node));
        setAfterPlayWaitTicks(XMLUtils.parseInteger(random, "afterPlayWaitTicks", node));

        nodeList = XMLUtils.getNodeList("map", node);
        entries = nodeList.getLength();

        Map<String, DeviceChannel> channelMap = new HashMap<String, DeviceChannel>();

        for (int i = 0; i < entries; i++) {
            String instrument = XMLUtils.parseString(random, "@instrument", nodeList.item(i));
            String device = XMLUtils.parseString(random, "@device", nodeList.item(i));
            int channel = Integer.parseInt(XMLUtils.parseString(random, "@channel", nodeList.item(i))) - 1;

            if (channelMap.containsKey(instrument)) {
                throw new RuntimeException("Instrument " + instrument + " must not be re-mapped");
            }

            if (!deviceMap.containsKey(device)) {
                throw new RuntimeException("Device \"" + device + "\" unknown");
            }

            int program = -1;

            try {
                program = Integer.parseInt(XMLUtils.parseString(random, "@program", nodeList.item(i))) - 1;
            } catch (Exception e) {}

            DeviceChannel ch = new DeviceChannel(deviceMap.get(device), channel, program);
            channelMap.put(instrument, ch);
        }

        setChannelMap(channelMap);

        nodeList = XMLUtils.getNodeList("controllerValue", node);
        entries = nodeList.getLength();
        ControllerValue[] controllerValues = new ControllerValue[entries];

        for (int i = 0; i < entries; i++) {
            String device = XMLUtils.parseString(random, "@device", nodeList.item(i));
            int channel = Integer.parseInt(XMLUtils.parseString(random, "@channel", nodeList.item(i))) - 1;
            String controller = XMLUtils.parseString(random, "@controller", nodeList.item(i));
            int value = Integer.parseInt(XMLUtils.parseString(random, ".", nodeList.item(i)));

            controllerValues[i] = new ControllerValue(device, channel, controller, value);
        }

        setControllerValues(controllerValues);

        nodeList = XMLUtils.getNodeList("controllerLFO", node);
        entries = nodeList.getLength();
        ControllerLFO[] controllerLFOs = new ControllerLFO[entries];

        for (int i = 0; i < entries; i++) {
            int minValue = Integer.MIN_VALUE;
            int maxValue = Integer.MAX_VALUE;
            int minAmplitude = 0;
            int maxAmplitude = 0;

            boolean usesLegacyTags = false;

            try {
                minAmplitude = XMLUtils.parseInteger(random, "minimum", nodeList.item(i));
                usesLegacyTags = true;
            } catch (Exception e) {}

            try {
                maxAmplitude = XMLUtils.parseInteger(random, "maximum", nodeList.item(i));
                usesLegacyTags = true;
            } catch (Exception e) {}

            if (usesLegacyTags) {
                logger.warn("The tags \"minimum\" and \"maximum\" for LFOs have been deprecated. "
                        + "Use \"minAmplitude\" and \"maxAmplitude\" instead.");
            }

            try {
                minAmplitude = XMLUtils.parseInteger(random, "minAmplitude", nodeList.item(i));
            } catch (Exception e) {}

            try {
                maxAmplitude = XMLUtils.parseInteger(random, "maxAmplitude", nodeList.item(i));
            } catch (Exception e) {}

            if (minAmplitude > maxAmplitude) {
                throw new RuntimeException("minAmplitude must be <= maxAmplitude");
            }

            try {
                minValue = XMLUtils.parseInteger(random, "minValue", nodeList.item(i));
            } catch (Exception e) {}

            try {
                maxValue = XMLUtils.parseInteger(random, "maxValue", nodeList.item(i));
            } catch (Exception e) {}

            if (minValue > maxValue) {
                throw new RuntimeException("minValue must be <= maxValue");
            }

            double speed = XMLUtils.parseDouble(random, XMLUtils.getNode("speed", nodeList.item(i)));

            String controller = XMLUtils.parseString(random, "controller", nodeList.item(i));

            String device = null;
            int channel = -1;

            if (!controller.equals("milliBPM")) {
                device = XMLUtils.parseString(random, "device", nodeList.item(i));
                channel = XMLUtils.parseInteger(random, "channel", nodeList.item(i)) - 1;
            }

            String rotationUnit = XMLUtils.parseString(random, "rotationUnit", nodeList.item(i));

            double phase = 0.0d;

            try {
                phase = XMLUtils.parseDouble(random, XMLUtils.getNode("phase", nodeList.item(i)));
            } catch (Exception e) {}

            String instrument = null;

            try {
                instrument = XMLUtils.parseString(random, "instrument", nodeList.item(i));
            } catch (Exception e) {}

            String activityVector = null;

            try {
                activityVector = XMLUtils.parseString(random, "activityVector", nodeList.item(i));
            } catch (Exception e) {}

            if (rotationUnit.equals("activity") && (instrument == null || instrument.equals("")) && (activityVector == null || activityVector.equals(
                    ""))) {
                throw new RuntimeException("Rotation unit \"activity\" requires an instrument or an ActivityVector");
            }

            if (rotationUnit.equals("segmentPair") && (activityVector == null || activityVector.equals(""))) {
                throw new RuntimeException("Rotation unit \"segmentPair\" requires an ActivityVector");
            }

            if (instrument != null && activityVector != null) {
                throw new RuntimeException("Either ActivityVector or instrument must be set, but not both");
            }

            Node lfoNode = XMLUtils.getNode("lfo", nodeList.item(i));

            LFO lfo;

            try {
                lfo = XMLUtils.getInstance(songContext, LFO.class, lfoNode, randomSeed, i);
            } catch (Exception e) {
                throw new RuntimeException("Could not instantiate LFO", e);
            }

            lfo.setSongContext(songContext);
            lfo.setMinAmplitude(minAmplitude);
            lfo.setMaxAmplitude(maxAmplitude);
            lfo.setMinValue(minValue);
            lfo.setMaxValue(maxValue);

            controllerLFOs[i] = new ControllerLFO(lfo, device, channel, controller, activityVector, instrument, speed, rotationUnit, phase);
        }

        setControllerLFOs(controllerLFOs);

        nodeList = XMLUtils.getNodeList("instrumentControllerLFO", node);
        entries = nodeList.getLength();
        InstrumentControllerLFO[] instrumentControllerLFOs = new InstrumentControllerLFO[entries];

        for (int i = 0; i < entries; i++) {
            String controller = XMLUtils.parseString(random, "controller", nodeList.item(i));

            String device = null;
            int channel = -1;

            if (!controller.equals("milliBPM")) {
                device = XMLUtils.parseString(random, "device", nodeList.item(i));
                channel = XMLUtils.parseInteger(random, "channel", nodeList.item(i)) - 1;
            }

            String instrument = XMLUtils.parseString(random, "instrument", nodeList.item(i));
            String lfoName = XMLUtils.parseString(random, "lfo", nodeList.item(i));

            int minValue = Integer.MIN_VALUE;
            int maxValue = Integer.MAX_VALUE;
            int minAmplitude = 0;
            int maxAmplitude = 0;

            try {
                minAmplitude = XMLUtils.parseInteger(random, "minAmplitude", nodeList.item(i));
            } catch (Exception e) {}

            try {
                maxAmplitude = XMLUtils.parseInteger(random, "maxAmplitude", nodeList.item(i));
            } catch (Exception e) {}

            if (minAmplitude > maxAmplitude) {
                throw new RuntimeException("minAmplitude must be <= maxAmplitude");
            }

            try {
                minValue = XMLUtils.parseInteger(random, "minValue", nodeList.item(i));
            } catch (Exception e) {}

            try {
                maxValue = XMLUtils.parseInteger(random, "maxValue", nodeList.item(i));
            } catch (Exception e) {}

            if (minValue > maxValue) {
                throw new RuntimeException("minValue must be <= maxValue");
            }

            instrumentControllerLFOs[i] = new InstrumentControllerLFO(device, channel, controller, instrument, lfoName, minAmplitude, maxAmplitude,
                    minValue, maxValue);
        }

        setInstrumentControllerLFOs(instrumentControllerLFOs);
    }

    /**
     * Sends a MIDI message with the given status, data1 and data2 to the given device channel.
     * 
     * @param deviceChannel the device channel
     * @param status the MIDI status
     * @param data1 the first MIDI data
     * @param data2 the second MIDI data
     * 
     * @throws InvalidMidiDataException in case of invalid MIDI data
     */

    private void sendMidiMessage(DeviceChannel deviceChannel, int status, int data1, int data2) throws InvalidMidiDataException {
        sendMidiMessage(deviceChannel.device, deviceChannel.channel, status, data1, data2);
    }

    /**
     * Sends a MIDI message with the given status, data1 and data2 to the given channel on the given device.
     * 
     * @param device the device
     * @param channel the channel number (0-15)
     * @param status the MIDI status
     * @param data1 the first MIDI data
     * @param data2 the second MIDI data
     * 
     * @throws InvalidMidiDataException in case of invalid MIDI data
     */

    private void sendMidiMessage(MidiPlayer.Device device, int channel, int status, int data1, int data2) throws InvalidMidiDataException {
        ShortMessage sm = new ShortMessage();
        sm.setMessage(status, channel, data1, data2);
        device.receiver.send(sm, -1);
    }

    /**
     * Adds a MIDI message to the given MIDI track.
     * 
     * @param track the MIDI track
     * @param tick the tick
     * @param channel the MIDI channel number
     * @param status the MIDI status
     * @param data1 the first data byte
     * @param data2 the second data byte
     * 
     * @throws InvalidMidiDataException in case of a MIDI error
     */

    private void sendMidiMessage(javax.sound.midi.Track track, int tick, int channel, int status, int data1, int data2)
            throws InvalidMidiDataException {
        ShortMessage sm = new ShortMessage();
        sm.setMessage(status, channel, data1, data2);
        track.add(new MidiEvent(sm, (long) tick + 1));
    }

    /**
     * Sends a MIDI message with the given status to the given device.
     * 
     * @param device the device
     * @param status the MIDI status
     * 
     * @throws InvalidMidiDataException in case of invalid MIDI data
     */

    private void sendMidiMessage(Device device, int status) throws InvalidMidiDataException {
        ShortMessage sm = new ShortMessage();
        sm.setMessage(status);
        device.receiver.send(sm, -1);
    }

    /**
     * Skips to the specified tick. This is done by muting all channels, resetting the player state and then silently fast-forwarding to the specified
     * tick.
     * 
     * @param tick the tick
     * 
     * @return true if skipping was successful, false otherwise
     */

    @Override
    public boolean skipToTick(int tick) {
        if (tick < 0 || tick > songContext.getStructure().getTicks()) {
            return false;
        } else {
            this.skipToTick = tick;
            skipEnabled = true;
            return true;
        }
    }

    @Override
    public void abortPlay() {
        if (opened && !playFinished) {
            this.playAbortRequested = true;

            // wait until playing has finished

            synchronized (playFinishedLock) {
                try {
                    playFinishedLock.wait(ABORT_PLAY_TIMEOUT);
                } catch (InterruptedException e) {
                    // do nothing
                }
            }
        }
    }

    public void setBeforePlayWaitTicks(int preWaitTicks) {
        this.beforePlayWaitTicks = preWaitTicks;
    }

    public void setAfterPlayWaitTicks(int postWaitTicks) {
        this.afterPlayWaitTicks = postWaitTicks;
    }

    @Override
    public int getCurrentTick() {
        return currentTick;
    }

    public void setMidiFilename(String midiFilename) {
        this.midiFilename = midiFilename;
    }

    public final void setControllerValues(ControllerValue[] controllerValues) {
        this.controllerValues = controllerValues;
    }

    public void setMinWindowSize(int minWindowSize) {
        this.minWindowSize = minWindowSize;
    }

    public void setMaxWindowSize(int maxWindowSize) {
        this.maxWindowSize = maxWindowSize;
    }

    public void setWaitForStart(boolean waitForStart) {
        this.waitForStart = waitForStart;
    }

    /**
     * Container for a MIDI device.
     */

    private final class Device {
        /** The SoundHelix-internal MIDI device name. */
        private final String name;

        /** The system's MIDI device name. */
        private final String midiName;

        /** The MIDI device. */
        private MidiDevice midiDevice;

        /** The MIDI receiver. */
        private Receiver receiver;

        /** Flag for using MIDI clock synchronization. */
        private boolean useClockSynchronization;

        /**
         * Constructor.
         * 
         * @param name the device name
         * @param midiName the MIDI device name
         * @param useClockSynchronization flag indicating whether clock synchronization should be used
         */

        private Device(String name, String midiName, boolean useClockSynchronization) {
            if (name == null || name.equals("")) {
                throw new IllegalArgumentException("Name must not be null or empty");
            }

            if (midiName == null || midiName.equals("")) {
                throw new IllegalArgumentException("MIDI device name must not be null or empty");
            }

            this.name = name;
            this.midiName = midiName;
            this.useClockSynchronization = useClockSynchronization;
        }

        /**
         * Opens the MIDI device.
         */

        public void open() {
            try {
                String[] deviceNames = StringUtils.split(midiName, ',');
                midiDevice = findFirstMidiDevice(deviceNames, false);

                if (midiDevice == null) {
                    throw new RuntimeException("Could not find any configured MIDI device with MIDI IN");
                }

                midiDevice.open();
                receiver = midiDevice.getReceiver();
                logger.debug("Successfully opened MIDI device \"" + name + "\" (using \"" + midiDevice.getDeviceInfo().getName() + "\")");
            } catch (Exception e) {
                throw new RuntimeException("Error opening MIDI device", e);
            }
        }

        /**
         * Closes the MIDI device.
         */

        public void close() {
            // the underlying receiver is closed automatically if the MIDI device is closed

            if (midiDevice != null) {
                midiDevice.close();
                logger.debug("Successfully closed MIDI device \"" + name + "\"");
                midiDevice = null;
                receiver = null;
            }
        }
    }

    /**
     * Represents a synchronization device.
     */

    private final class SyncDevice {
        /** The system's MIDI device name. */
        private final String midiName;

        /** The MIDI device. */
        private MidiDevice midiDevice;

        /** The MIDI transmitter. */
        private Transmitter transmitter;

        /**
         * Constructor.
         * 
         * @param midiName the MIDI device name
         */

        private SyncDevice(String midiName) {
            if (midiName == null || midiName.equals("")) {
                throw new IllegalArgumentException("MIDI device name must not be null or empty");
            }

            this.midiName = midiName;
        }

        /**
         * Opens the MIDI device.
         */

        public void open() {
            try {
                String[] deviceNames = StringUtils.split(midiName, ',');
                midiDevice = findFirstMidiDevice(deviceNames, true);

                if (midiDevice == null) {
                    throw new RuntimeException("Could not find any configured MIDI device with MIDI IN");
                }

                midiDevice.open();
                transmitter = midiDevice.getTransmitter();
                transmitter.setReceiver(new MidiClockReceiver(minWindowSize, maxWindowSize));
                logger.debug("Successfully opened MIDI sync device (using \"" + midiDevice.getDeviceInfo().getName() + "\")");
            } catch (Exception e) {
                throw new RuntimeException("Error opening MIDI device", e);
            }
        }

        /**
         * Closes the MIDI device.
         */

        public void close() {
            // the underlying receiver is closed automatically if the MIDI device is closed

            if (midiDevice != null) {
                transmitter.getReceiver().close();
                transmitter.close();
                midiDevice.close();
                logger.debug("Successfully closed MIDI sync device \"" + midiName + "\"");
                midiDevice = null;
                transmitter = null;
            }
        }
    }

    /**
     * Container for the combination of device, channel and preselected program.
     */

    public static class DeviceChannel {
        /** The MIDI device. */
        private final Device device;

        /** The MIDI channel. */
        private final int channel;

        /** The MIDI program. */
        private final int program;

        public DeviceChannel(Device device, int channel, int program) {
            this.device = device;
            this.channel = channel;
            this.program = program;
        }

        @Override
        public final boolean equals(Object object) {
            if (!(object instanceof DeviceChannel)) {
                return false;
            } else if (this == object) {
                return true;
            }

            DeviceChannel other = (DeviceChannel) object;
            return device.equals(other.device) && channel == other.channel && program == other.program;
        }

        @Override
        public final int hashCode() {
            return device.hashCode() * 16273 + channel * 997 + program;
        }

        @Override
        public String toString() {
            return device.name + "/" + (channel + 1);
        }
    }

    /**
     * Container for MIDI controller values.
     */
    private static final class ControllerValue {
        /** The device name. */
        private final String deviceName;

        /** The MIDI channel. */
        private int channel;

        /** The name of the MIDI controller. */
        private String controller;

        /** The value of the MIDI controller. */
        private int value;

        /**
         * Constructor.
         * 
         * @param deviceName the device name
         * @param channel the MIDI channel number
         * @param controller the controller number
         * @param value the controller value
         */

        private ControllerValue(String deviceName, int channel, String controller, int value) {
            this.deviceName = deviceName;
            this.channel = channel;
            this.controller = controller;
            this.value = value;
        }
    }

    /**
     * Container for LFO configuration.
     */

    private static final class InstrumentControllerLFO {
        /** The device name. */
        private final String deviceName;

        /** The MIDI channel. */
        private int channel;

        /** The name of the MIDI controller. */
        private String controller;

        /** The instrument. */
        private String instrument;

        /** The LFO name of that instrument. */
        private String lfoName;

        /** The LFO. */
        private LFO lfo;

        /** The minimum amplitude. */
        private int minAmplitude;

        /** The maximum amplitude. */
        private int maxAmplitude;

        /** The minimum cut-off value. */
        private int minValue;

        /** The maximum cut-off value. */
        private int maxValue;

        /** The value last sent to the MIDI controller. */
        private int lastSentValue;

        /**
         * Constructor.
         * 
         * @param deviceName the device name
         * @param channel the MIDI channel number
         * @param controller the MIDI controller number
         * @param instrument the instrument name
         * @param lfoName the LFO name
         * @param minAmplitude the minimum amplitude
         * @param maxAmplitude the maximum amplitude
         * @param minValue the minimum value
         * @param maxValue the maximum value
         */

        private InstrumentControllerLFO(String deviceName, int channel, String controller, String instrument, String lfoName, int minAmplitude,
                int maxAmplitude, int minValue, int maxValue) {
            this.deviceName = deviceName;
            this.channel = channel;
            this.controller = controller;
            this.instrument = instrument;
            this.lfoName = lfoName;
            this.minAmplitude = minAmplitude;
            this.maxAmplitude = maxAmplitude;
            this.minValue = minValue;
            this.maxValue = maxValue;
        }
    }

    /**
     * Container for LFO configuration.
     */

    private static final class ControllerLFO {
        /** The LFO. */
        private LFO lfo;

        /** The device name. */
        private final String deviceName;

        /** The MIDI channel. */
        private int channel;

        /** The name of the MIDI controller. */
        private String controller;

        /** The ActivityVector. */
        private String activityVector;

        /** The instrument. */
        private String instrument;

        /** The LFO speed in radians. */
        private double speed;

        /** The LFO rotation unit. */
        private String rotationUnit;

        /** The LFO phase in radians. */
        private double phase;

        /** The value last sent to the MIDI controller. */
        private int lastSentValue;

        /**
         * Constructor.
         * 
         * @param lfo the LFO
         * @param deviceName the device name
         * @param channel the MIDI channel number
         * @param controller the MIDI controller number
         * @param activityVector the ActivityVector name
         * @param instrument the instrument name
         * @param speed the speed
         * @param rotationUnit the rotation unit
         * @param phase the phase
         */

        private ControllerLFO(LFO lfo, String deviceName, int channel, String controller, String activityVector, String instrument, double speed,
                String rotationUnit, double phase) {
            this.lfo = lfo;
            this.deviceName = deviceName;
            this.channel = channel;
            this.controller = controller;
            this.activityVector = activityVector;
            this.instrument = instrument;
            this.speed = speed;
            this.rotationUnit = rotationUnit;
            this.phase = phase;
        }
    }

    private static final class MidiController {
        /** The MIDI status byte. */
        private int status;

        /** The parameter. */
        private int parameter;

        /** The number of bytes. */
        private int byteCount;

        /**
         * Constructor.
         * 
         * @param status the MIDI status
         * @param byteCount the number of bytes
         */

        private MidiController(int status, int byteCount) {
            this.status = status;
            this.parameter = -1;
            this.byteCount = byteCount;
        }

        /**
         * Constructor.
         * 
         * @param status the MIDI status
         * @param parameter the parameter
         * @param byteCount the number of bytes
         */

        private MidiController(int status, int parameter, int byteCount) {
            this.status = status;
            this.parameter = parameter;
            this.byteCount = byteCount;
        }
    }

    /**
     * Implements a receiver for MIDI clock messages. The obtained timing is averaged across a number of timing ticks using a moving average, and the
     * resulting BPM are set for the player.
     */

    private final class MidiClockReceiver implements Receiver {
        /** True if this is the first tick. */
        private boolean firstTick = true;

        /** The last time a tick was received. */
        private long lastTime;

        /** The sum of the time differences of the queue. */
        private long sum;

        /** The queue of time differences. */
        private final Queue<Long> timeQueue;

        /** The number of ticks received so far. */
        private int count;

        /** The minimum window size for BPM calculation. */
        private int minWindowSize = 12;

        /** The maximum window size for BPM calculation. */
        private int maxWindowSize = 24;

        /**
         * Constructor.
         * 
         * @param minWindowSize the minimum window size
         * @param maxWindowSize the maximum window size
         */

        private MidiClockReceiver(int minWindowSize, int maxWindowSize) {
            if (minWindowSize < 1) {
                throw new IllegalArgumentException("minWindowSize must be >= 1");
            }

            if (maxWindowSize < 1) {
                throw new IllegalArgumentException("maxWindowSize must be >= 1");
            }

            if (maxWindowSize < minWindowSize) {
                throw new IllegalArgumentException("maxWindowSize must be >= minWindowSize");
            }

            this.minWindowSize = minWindowSize;
            this.maxWindowSize = maxWindowSize;
            timeQueue = new ArrayBlockingQueue<Long>(maxWindowSize);
        }

        @Override
        public void send(MidiMessage message, long timeStamp) {
            int status = message.getStatus();
            if (status == ShortMessage.TIMING_CLOCK) {
                long time = System.nanoTime();

                if (!firstTick) {
                    long diff = time - lastTime;

                    if (timeQueue.size() >= maxWindowSize) {
                        // keep queue size constant
                        sum -= timeQueue.remove();
                    }

                    timeQueue.add(diff);
                    sum += diff;

                    if (timeQueue.size() >= minWindowSize) {
                        long milliBPM = 60000000000000L / 24L * timeQueue.size() / sum;
                        setMilliBPM((int) milliBPM);
                    }
                } else {
                    firstTick = false;
                }

                lastTime = time;
                count++;

                if (count % maxWindowSize == 0) {
                    logger.trace("Milli BPM from MIDI sync: " + milliBPM);
                }
            } else if (status == ShortMessage.START || status == ShortMessage.CONTINUE) {
                logger.debug("Received START/CONTINUE MIDI message");
                firstTick = true;
                timeQueue.clear();
                running = true;
                sum = 0;
            } else if (message.getStatus() == ShortMessage.STOP) {
                logger.debug("Received STOP MIDI message");
                running = false;
            }
        }

        @Override
        public void close() {
            // do nothing
        }
    }
}
