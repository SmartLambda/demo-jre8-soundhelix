$(function () {
    var player;
    var resetPlayState = function() {
        var $button = $('#try');

        $button.removeClass("btn-default");
        $button.removeClass("btn-danger");
        $button.addClass("btn-success");
        $button.html("<span class='glyphicon glyphicon-play'></span> Generate and play");

        if (typeof player !== 'undefined') {
            player.stop();
        }
    };

    navigator.requestMIDIAccess().then(function(midi) {
        midi.outputs.forEach(function(device) {
            $('#midi-output').append("<option value='" + device.id + "'>" + device.name + "</option>");
        });
    });

    $('#preset').change(function() {
        var $instrumentsTableBody = $('#instruments-table').find("tbody");
        $instrumentsTableBody.html("");

        var addInstrument = function(name, channel, program) {
            $instrumentsTableBody.append("<tr>" +
                "<td>" + name + "</td>" +
                "<td><input class='form-control' type='number' value='" + channel + "' name='channel[" + name + "]'></td>" +
                "<td><input class='form-control' type='number' value='" + program + "' name='program[" + name + "]'></td>" +
                "</tr>");
        };

        switch($(this).find(":selected").val()) {
            case "piano":
                addInstrument("arpeggio", 1, 1);
                addInstrument("accomp", 2, 1);
                addInstrument("melody", 3, 2);
                addInstrument("pad", 4, 92);
                addInstrument("bass", 5, 40);
                addInstrument("randombass", 6, 40);
                addInstrument("percussion", 10, "")
                break;
            case "percussion":
                addInstrument("percussion", 10, "");
                break;
            case "popcorn":
                addInstrument("percussion", 10, "");
                addInstrument("melody", 1, 1);
                addInstrument("chords", 2, 1);
                addInstrument("accomp", 3, 1);
                addInstrument("bass", 4, 36);
                addInstrument("string", 5, 41);
                addInstrument("pad", 8, 83);
                addInstrument("arpeggio", 9, 9);
                break;
        }
    }).change();

    var buildParameters = function() {
        var parameters = {
            bpm: $('#bpm').val(),
            maxVelocity: $('#maxVelocity').val(),
            groove: $('#groove').val(),
            ticksPerBeat: $('#ticksPerBeat').val(),
            arrangement: $('#preset').find(":selected").val(),
            midiChannels: {},
            midiPrograms: {}
        };

        $('#instruments-table').find("tbody > tr").each(function() {
            parameters.midiChannels[$(this).find("td:nth-child(1)").html()] = parseInt($(this).find("td:nth-child(2) > input").val());
            parameters.midiPrograms[$(this).find("td:nth-child(1)").html()] = parseInt($(this).find("td:nth-child(3) > input").val());
        });

        return parameters;
    };

    var play = function(parameters) {
        $.post({
            url: SMARTLAMBDA_ENDPOINT + "/" + LAMBDA_OWNER + "/lambda/" + LAMBDA_NAME,
            success: function(result) {
                var buf = new ArrayBuffer(result.midi.length);
                var array = new Uint8Array(buf);
                array.set(result.midi);

                var $try = $('#try');

                navigator.requestMIDIAccess().then(function(midi) {
                    var device = midi.outputs.get($('#midi-output').find(":selected").val());
                    player = new MIDIPlayer({
                        'output': device
                    });

                    player.load(new MIDIFile(buf));
                    player.volume = 100;

                    player.play(function() {
                        resetPlayState();
                    });

                    $try.removeClass("btn-default");
                    $try.addClass("btn-danger");
                    $try.html("<span class='glyphicon glyphicon-stop'></span> Stop playback");
                });
            },
            data: JSON.stringify({
                parameters: parameters
            }),
            dataType: "json",
            headers: {
                "SmartLambda-Key": SMARTLAMBDA_KEY
            },
            contentType: "application/json"
        });
    };

    $('#try').click(function () {
        if (typeof player !== 'undefined' && player.pause()) {
            resetPlayState();
            return;
        }

        var self = $(this);

        self.removeClass("btn-success");
        self.addClass("btn-default");
        self.html("<i class='fa fa-spinner fa-spin'></i> Generating...");

        play(buildParameters());
    });

    $('#set-alarm').click(function() {
        var parameters = buildParameters();
        var date = new Date();
        date.setHours($('#hour').val());
        date.setMinutes($('#minute').val());
        date.setSeconds(0);

        setTimeout(function() {
            console.log("alarm!");
            play(parameters);
        }, date.getTime() - Date.now());
    });
});