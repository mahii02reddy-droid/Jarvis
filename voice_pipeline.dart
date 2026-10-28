import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter_tts/flutter_tts.dart';
import 'package:speech_to_text/speech_to_text.dart';

import 'ai_parser.dart';
import 'contact_finder.dart';
import 'native.dart';
import 'settings.dart';

class VoicePipeline {
  VoicePipeline._();
  static final VoicePipeline i = VoicePipeline._();

  final status = ValueNotifier<String>('Stopped');
  final logs = ValueNotifier<List<String>>([]);
  bool running = false;

  final _stt = SpeechToText();
  final _tts = FlutterTts();
  bool _sttReady = false;
  Completer<String>? _pending;
  String _last = '';

  static const _wake = ['jarvis', 'jarvish', 'service', 'जार्विस', 'जर्विस', 'జార్విస్'];

  void _log(String m) {
    final t = DateTime.now().toString().substring(11, 19);
    logs.value = ['$t  $m', ...logs.value].take(60).toList();
  }

  Future<bool> start() async {
    if (running) return true;
    try {
      await _tts.awaitSpeakCompletion(true);
      _sttReady = await _stt.initialize(
        onError: (e) {
          if (e.errorMsg != 'error_no_match' && e.errorMsg != 'error_speech_timeout') {
            _log('Speech: ${e.errorMsg}');
          }
        },
        onStatus: (s) {
          if ((s == 'done' || s == 'notListening') &&
              _pending != null &&
              !_pending!.isCompleted) {
            _pending!.complete(_last);
          }
        },
      );
      if (!_sttReady) {
        _log('Speech recognition not available. Check microphone permission.');
        return false;
      }
      await Native.startService();
      running = true;
      _log('Started');
      unawaited(_loop());
      return true;
    } catch (e) {
      _log('Start failed: $e');
      return false;
    }
  }

  Future<void> stop() async {
    running = false;
    try {
      await _stt.cancel();
    } catch (_) {}
    if (_pending != null && !_pending!.isCompleted) _pending!.complete('');
    await Native.stopService();
    status.value = 'Stopped';
    _log('Stopped');
  }

  Future<void> _loop() async {
    while (running) {
      try {
        status.value = 'Listening for "Jarvis"';
        final heard = await _listen(const Duration(seconds: 30), const Duration(seconds: 4));
        if (!running) break;
        var cmd = _afterWake(heard);
        if (cmd == null) {
          await Future.delayed(const Duration(milliseconds: 300));
          continue;
        }
        _log('Wake word heard');
        if (cmd.isEmpty) {
          await Native.beep();
          status.value = 'Yes? Speak now';
          cmd = (await _listen(const Duration(seconds: 15), const Duration(seconds: 3))).trim();
        }
        _log('Heard: "$cmd"');
        if (cmd.isEmpty) {
          await _say("Sorry, I didn't catch that");
          continue;
        }
        status.value = 'Working…';
        await _execute(cmd);
      } catch (e) {
        _log('Error: $e');
        await Future.delayed(const Duration(seconds: 1));
      }
    }
  }

  String? _afterWake(String heard) {
    final lower = heard.toLowerCase();
    for (final w in _wake) {
      final idx = lower.indexOf(w);
      if (idx >= 0) {
        return lower
            .substring(idx + w.length)
            .replaceFirst(RegExp(r'^[\s,.:;!\-]+'), '')
            .trim();
      }
    }
    return null;
  }

  Future<String> _listen(Duration listenFor, Duration pauseFor) async {
    _last = '';
    final done = Completer<String>();
    _pending = done;
    await _stt.listen(
      onResult: (r) {
        _last = r.recognizedWords;
        if (r.finalResult && !done.isCompleted) done.complete(_last);
      },
      listenFor: listenFor,
      pauseFor: pauseFor,
      localeId: Settings.i.str('locale', 'en_IN'),
    );
    return done.future.timeout(listenFor + const Duration(seconds: 3), onTimeout: () {
      _stt.stop();
      return _last;
    });
  }

  Future<void> _execute(String text) async {
    final cmd = await AiParser.parse(text);
    if (cmd.action != 'whatsapp' || cmd.contact == null || cmd.message == null) {
      await _say(cmd.reply ?? 'Sorry, I can only send WhatsApp messages for now');
      return;
    }
    final c = await ContactFinder.find(cmd.contact!);
    if (c == null) {
      await _say("I couldn't find ${cmd.contact} in your contacts");
      return;
    }
    if (await Native.isLocked()) {
      await _say('Phone is locked. Please unlock to send');
      var waited = 0;
      while (await Native.isLocked() && waited < 30) {
        await Future.delayed(const Duration(seconds: 1));
        waited++;
      }
      if (await Native.isLocked()) {
        await _say('Cancelled, the phone stayed locked');
        return;
      }
    }
    _log('Sending to ${c.name} (${c.number}): ${cmd.message}');
    if (!await Native.openWhatsApp(c.number, cmd.message!)) {
      await _say('Please turn on Accessibility for Jarvis in Setup');
      return;
    }
    for (var i = 0; i < 15; i++) {
      await Future.delayed(const Duration(seconds: 1));
      if (await Native.consumeSent()) {
        await _say('Message sent to ${c.name}');
        return;
      }
    }
    await _say('I opened the chat but could not press send');
  }

  Future<void> _say(String t) async {
    _log('Jarvis: $t');
    status.value = t;
    await _tts.speak(t);
  }
}
