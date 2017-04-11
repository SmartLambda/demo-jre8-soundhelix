package edu.teco.smartlambda.demo;

import com.soundhelix.component.player.impl.MidiPlayer;
import com.soundhelix.misc.SongContext;
import com.soundhelix.util.SongUtils;
import edu.teco.smartlambda.execution.LambdaFunction;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.*;
import java.util.Random;

public class SoundHelixLambda {
    private static final String OUTPUT_PATH = "/tmp/output.mid";

    @LambdaFunction
    public MIDIResponse maestro(final Parameters parameters) throws Exception {
        final SongContext songContext = SongUtils.generateSong(new ByteArrayInputStream
                        (buildConfigurationFromParameters(parameters).toByteArray()),
                "SoundHelixLambda.xml", String.valueOf(new Random().nextInt()));

        songContext.getPlayer().saveMidiFiles(songContext);

        final MIDIResponse response = new MIDIResponse();
        final File output = new File(OUTPUT_PATH);
        final byte[] midi = new byte[(int) output.length()];

        new DataInputStream(new FileInputStream(output)).readFully(midi);
        response.setMidi(midi);

        return response;
    }

    private ByteArrayOutputStream buildConfigurationFromParameters(final Parameters parameters) throws ParserConfigurationException,
            IOException, SAXException, TransformerException {
        final Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
        final Element root = document.createElement("SoundHelix");
        document.appendChild(root);

        final Element player = document.createElement("player");
        player.setAttribute("class", MidiPlayer.class.getSimpleName());
        root.appendChild(player);

        final Element arrangement = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(getClass()
                .getResourceAsStream("/arrangements/" + parameters.getArrangement() + ".xml")).getDocumentElement();

        parameters.getMidiChannels().forEach((name, channel) -> {
            final Element map = document.createElement("map");
            map.setAttribute("instrument", name);
            map.setAttribute("device", "device1");
            map.setAttribute("channel", String.valueOf(channel));

            final Integer program = parameters.getMidiPrograms().get(name);
            if(program != null && program != 0)
                map.setAttribute("program", String.valueOf(program));

            player.appendChild(map);
        });

        for (int i = 0; i < arrangement.getChildNodes().getLength(); i++) {
            final Node item = arrangement.getChildNodes().item(i);
            final Node imported = document.importNode(item, true);
            root.appendChild(imported);
        }

        final Element structure = document.createElement("structure");
        root.appendChild(structure);

        final Element bars = document.createElement("bars");
        bars.setTextContent("96");
        structure.appendChild(bars);

        final Element beatsPerBar = document.createElement("beatsPerBar");
        beatsPerBar.setTextContent("4");
        structure.appendChild(beatsPerBar);

        final Element ticksPerBeat = document.createElement("ticksPerBeat");
        ticksPerBeat.setTextContent(String.valueOf(parameters.getTicksPerBeat()));
        structure.appendChild(ticksPerBeat);

        final Element maxVelocity = document.createElement("maxVelocity");
        maxVelocity.setTextContent(String.valueOf(parameters.getMaxVelocity()));
        structure.appendChild(maxVelocity);

        final Element bpm = document.createElement("bpm");
        bpm.setTextContent(String.valueOf(parameters.getBpm()));
        player.appendChild(bpm);

        final Element transposition = document.createElement("transposition");
        transposition.setTextContent("66");
        player.appendChild(transposition);

        final Element beforePlayWaitTicks = document.createElement("beforePlayWaitTicks");
        beforePlayWaitTicks.setTextContent("0");
        player.appendChild(beforePlayWaitTicks);

        final Element afterPlayWaitTicks = document.createElement("afterPlayWaitTicks");
        afterPlayWaitTicks.setTextContent("0");
        player.appendChild(afterPlayWaitTicks);

        final Element midiFilename = document.createElement("midiFilename");
        midiFilename.setTextContent(OUTPUT_PATH);
        player.appendChild(midiFilename);

        final Element device = document.createElement("device");
        device.setAttribute("name", "device1");
        device.setAttribute("clockSynchronization", "true");
        device.setTextContent("Gervill");
        player.appendChild(device);

        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        TransformerFactory.newInstance().newTransformer().transform(new DOMSource(document), new StreamResult
                (outputStream));

        return outputStream;
    }
}
