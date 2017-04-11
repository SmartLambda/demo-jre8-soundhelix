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

    $('#try').click(function () {
        if (typeof player !== 'undefined' && player.pause()) {
            resetPlayState();
            return;
        }

        var self = $(this);

        self.removeClass("btn-success");
        self.addClass("btn-default");
        self.html("<i class='fa fa-spinner fa-spin'></i> Generating...");

        $.post({
            url: SMARTLAMBDA_ENDPOINT + "/" + LAMBDA_OWNER + "/lambda/" + LAMBDA_NAME,
            success: function(result) {
                var buf = new ArrayBuffer(result.midi.length);
                var array = new Uint8Array(buf);
                array.set(result.midi);

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

                    self.removeClass("btn-default");
                    self.addClass("btn-danger");
                    self.html("<span class='glyphicon glyphicon-stop'></span> Stop playback");
                });
            },
            data: JSON.stringify({
                parameters: {
                    bpm: $('#bpm').val(),
                    maxVelocity: $('#maxVelocity').val(),
                    groove: $('#groove').val(),
                    ticksPerBeat: $('#ticksPerBeat').val(),
                    arrangement: $('#preset').find(":selected").val()
                }
            }),
            dataType: "json",
            headers: {
                "SmartLambda-Key": SMARTLAMBDA_KEY
            },
            contentType: "application/json"
        });
    });
});