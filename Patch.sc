set -e
S=mahesh_jarvis/source
J=$S/android_overlay/app/src/main/java/com/mahesh/jarvis

cat > $S/lib/services/native.dart <<'EOF'
import 'package:flutter/services.dart';

/// Bridge to the Java code in android/ (service, accessibility, lock state).
class Native {
  static const _c = MethodChannel('jarvis/native');
  static Future<bool> _b(String m, [Object? a]) async =>
      (await _c.invokeMethod<bool>(m, a)) ?? false;

  static Future<void> startService() => _c.invokeMethod('startService');
  static Future<void> stopService() => _c.invokeMethod('stopService');
  static Future<void> beep() => _c.invokeMethod('beep');

  static Future<bool> isLocked() => _b('isLocked');
  static Future<bool> consumeSent() => _b('consumeSent');
  static Future<bool> openWhatsApp(String number, String text) =>
      _b('openWhatsApp', {'number': number, 'text': text});

  static Future<bool> openApp(String name) => _b('openApp', {'name': name});
  static Future<bool> callNumber(String number) => _b('callNumber', {'number': number});
  static Future<bool> smsNumber(String number, String text) =>
      _b('smsNumber', {'number': number, 'text': text});

  static Future<bool> accessibilityOn() => _b('isAccessibilityEnabled');
  static Future<bool> overlayOn() => _b('canDrawOverlays');
  static Future<bool> batteryOk() => _b('isBatteryUnrestricted');
  static Future<void> openAccessibility() => _c.invokeMethod('openAccessibilitySettings');
  static Future<void> openOverlay() => _c.invokeMethod('openOverlaySettings');
  static Future<void> openBattery() => _c.invokeMethod('openBatterySettings');
}
EOF

cat > $S/lib/services/ai_parser.dart <<'EOF'
import 'dart:convert';
import 'package:http/http.dart' as http;
import 'settings.dart';

class Command {
  final String action; // whatsapp | open_app | call | sms | reply
  final String? contact, message, app, reply;
  Command(this.action, {this.contact, this.message, this.app, this.reply});
}

class AiParser {
  static const _system = '''
You are the brain of a voice assistant on an Android phone. The user speaks English, Hindi, Telugu or a mix (maybe transliterated). Reply with ONE JSON object only, no prose, no code fences.
Choose exactly one of these:
{"action":"open_app","app":"<app name as shown on the phone, for example Instagram>"}
{"action":"call","contact":"<contact name in Latin letters>"}
{"action":"sms","contact":"<contact name in Latin letters>","message":"<text to send>"}
{"action":"whatsapp","contact":"<contact name in Latin letters>","message":"<text to send>"}
{"action":"reply","text":"<spoken answer, at most 40 words, in the language of the user>"}
Use "reply" for questions, chat, and anything you cannot do on the phone.''';

  static Future<Command> parse(String text) async {
    final key = Settings.i.str('claude');
    if (key.isNotEmpty) {
      try {
        return await _claude(text, key);
      } catch (_) {/* fall back to local rules */}
    }
    return _local(text);
  }

  static Future<Command> _claude(String text, String key) async {
    final r = await http
        .post(
          Uri.parse('https://api.anthropic.com/v1/messages'),
          headers: {
            'x-api-key': key,
            'anthropic-version': '2023-06-01',
            'content-type': 'application/json',
          },
          body: jsonEncode({
            'model': 'claude-haiku-5-5',
            'max_tokens': 400,
            'system': _system,
            'messages': [
              {'role': 'user', 'content': text}
            ],
          }),
        )
        .timeout(const Duration(seconds: 15));
    if (r.statusCode != 200) throw Exception('API ${r.statusCode}');
    final raw = jsonDecode(utf8.decode(r.bodyBytes))['content'][0]['text'] as String;
    final a = raw.indexOf('{');
    final b = raw.lastIndexOf('}');
    final j = jsonDecode(raw.substring(a, b + 1)) as Map<String, dynamic>;
    final action = j['action']?.toString() ?? 'reply';
    if (action == 'open_app') return Command('open_app', app: j['app']?.toString());
    if (action == 'call') return Command('call', contact: j['contact']?.toString());
    if (action == 'sms') {
      return Command('sms', contact: j['contact']?.toString(), message: j['message']?.toString());
    }
    if (action == 'whatsapp') {
      return Command('whatsapp', contact: j['contact']?.toString(), message: j['message']?.toString());
    }
    return Command('reply', reply: j['text']?.toString());
  }

  /// Offline fallback when no Claude key is set.
  static Command _local(String raw) {
    final t = raw.trim();
    var m = RegExp(r'^(?:open|launch|start)\s+(.+)$', caseSensitive: false).firstMatch(t);
    if (m != null) return Command('open_app', app: m.group(1));
    m = RegExp(r'^(?:call|phone|dial)\s+(.+)$', caseSensitive: false).firstMatch(t);
    if (m != null) return Command('call', contact: m.group(1));
    m = RegExp(r'^(?:sms|text)\s+(?:to\s+)?(\w+)\s+(.+)$', caseSensitive: false).firstMatch(t);
    if (m != null) return Command('sms', contact: m.group(1), message: m.group(2));
    m = RegExp(
      r'^(?:send\s+(?:a\s+)?(?:whatsapp\s+)?(?:message|msg)|message|msg|whatsapp)\s+(?:to\s+)?(\w+)\s+(?:on\s+whatsapp\s+)?(?:saying\s+|that\s+|say\s+)?(.+)$',
      caseSensitive: false,
    ).firstMatch(t);
    if (m != null) return Command('whatsapp', contact: m.group(1), message: m.group(2));
    return Command('reply',
        reply: 'Without a Claude API key I only understand: open an app, call a name, text a name, or message a name.');
  }
}
EOF

cat > $S/lib/services/voice_pipeline.dart <<'EOF'
import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter_tts/flutter_tts.dart';
import 'package:speech_to_text/speech_to_text.dart';

import 'ai_parser.dart';
import 'contact_finder.dart';
import 'native.dart';
import 'settings.dart';

/// Tap the mic -> beep -> speak a command -> Claude/rules parse -> action.
class VoicePipeline {
  VoicePipeline._();
  static final VoicePipeline i = VoicePipeline._();

  final status = ValueNotifier<String>('Tap the mic and speak');
  final logs = ValueNotifier<List<String>>([]);
  bool running = false;

  final _stt = SpeechToText();
  final _tts = FlutterTts();
  bool _sttReady = false;
  Completer<String>? _pending;
  String _last = '';
  int _gen = 0;

  void _log(String m) {
    final t = DateTime.now().toString().substring(11, 19);
    logs.value = ['$t  $m', ...logs.value].take(60).toList();
  }

  Future<bool> start() async {
    if (running) return true;
    running = true;
    final gen = ++_gen;
    status.value = 'Starting…';
    unawaited(_session(gen));
    return true;
  }

  Future<void> stop() async {
    _gen++;
    running = false;
    try {
      await _stt.cancel();
    } catch (_) {}
    if (_pending != null && !_pending!.isCompleted) _pending!.complete('');
    status.value = 'Tap the mic and speak';
  }

  Future<bool> _init() async {
    if (_sttReady) return true;
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
    return _sttReady;
  }

  Future<void> _session(int gen) async {
    try {
      await _tts.awaitSpeakCompletion(true);
      if (!await _init()) {
        _log('Speech recognition not available. Check microphone permission in Setup.');
        return;
      }
      await Native.beep();
      status.value = 'Listening… speak now';
      var text = (await _listen(const Duration(seconds: 15), const Duration(seconds: 3))).trim();
      if (gen != _gen) return;
      text = text
          .replaceFirst(
              RegExp(r'^(?:hey\s+|ok\s+|okay\s+)?jarvis\b[\s,.:;!\-]*', caseSensitive: false), '')
          .trim();
      _log('Heard: "$text"');
      if (text.isEmpty) {
        await _say("Sorry, I didn't catch that");
        return;
      }
      status.value = 'Working…';
      await _execute(text);
    } catch (e) {
      _log('Error: $e');
    } finally {
      if (gen == _gen) {
        running = false;
        status.value = 'Tap the mic and speak';
      }
    }
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
    switch (cmd.action) {
      case 'open_app':
        {
          final app = cmd.app ?? '';
          await _say('Opening $app');
          if (!await Native.openApp(app)) await _say("I couldn't find an app called $app");
          return;
        }
      case 'call':
        {
          final c = await _contact(cmd.contact);
          if (c == null) return;
          await _say('Calling ${c.name}');
          if (!await Native.callNumber('+${c.number}')) await _say("I couldn't start the call");
          return;
        }
      case 'sms':
        {
          final c = await _contact(cmd.contact);
          if (c == null) return;
          await _say('Opening a message to ${c.name}');
          if (!await Native.smsNumber('+${c.number}', cmd.message ?? '')) {
            await _say("I couldn't open the messages app");
          }
          return;
        }
      case 'whatsapp':
        await _whatsapp(cmd);
        return;
      default:
        await _say(cmd.reply ?? "Sorry, I didn't understand that");
    }
  }

  Future<FoundContact?> _contact(String? name) async {
    if (name == null || name.trim().isEmpty) {
      await _say('Who should I contact?');
      return null;
    }
    final c = await ContactFinder.find(name);
    if (c == null) await _say("I couldn't find $name in your contacts");
    return c;
  }

  Future<void> _whatsapp(Command cmd) async {
    if (cmd.message == null || cmd.message!.trim().isEmpty) {
      await _say('What should I say?');
      return;
    }
    final c = await _contact(cmd.contact);
    if (c == null) return;
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
EOF

cat > $J/Actions.java <<'EOF'
package com.mahesh.jarvis;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;

import java.util.List;
import java.util.Locale;

/** Open apps, place calls, prefill SMS. */
public class Actions {
    public static boolean openApp(Context ctx, String name) {
        if (name == null) return false;
        String q = name.toLowerCase(Locale.ROOT).trim();
        if (q.isEmpty()) return false;
        PackageManager pm = ctx.getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = pm.queryIntentActivities(main, 0);
        ResolveInfo best = null;
        int bestScore = 0;
        for (ResolveInfo ri : apps) {
            String label = ri.loadLabel(pm).toString().toLowerCase(Locale.ROOT);
            int s = 0;
            if (label.equals(q)) s = 4;
            else if (label.startsWith(q)) s = 3;
            else if (label.contains(q)) s = 2;
            else if (label.length() >= 3 && q.contains(label)) s = 1;
            if (s > bestScore) { bestScore = s; best = ri; }
        }
        if (best == null) return false;
        Intent i = pm.getLaunchIntentForPackage(best.activityInfo.packageName);
        if (i == null) return false;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { ctx.startActivity(i); return true; } catch (Exception e) { return false; }
    }

    public static boolean call(Context ctx, String number) {
        if (number == null) return false;
        try {
            Intent i = new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + number));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return true;
        } catch (SecurityException se) {
            try {
                Intent d = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + number));
                d.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(d);
                return true;
            } catch (Exception e2) { return false; }
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean sms(Context ctx, String number, String text) {
        if (number == null) return false;
        try {
            Intent i = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + number));
            i.putExtra("sms_body", text == null ? "" : text);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
EOF

python3 - <<'PY'
import re
S = "mahesh_jarvis/source"
# 1) MainActivity: add new channel methods before default:
p = S + "/android_overlay/app/src/main/java/com/mahesh/jarvis/MainActivity.java"
t = open(p).read()
marker = "                        default:"
assert t.count(marker) == 1, "MainActivity marker"
new = '''                        case "openApp":
                            result.success(Actions.openApp(this, call.argument("name")));
                            break;
                        case "callNumber":
                            if (checkSelfPermission(android.Manifest.permission.CALL_PHONE)
                                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                requestPermissions(new String[]{android.Manifest.permission.CALL_PHONE}, 77);
                            }
                            result.success(Actions.call(this, call.argument("number")));
                            break;
                        case "smsNumber":
                            result.success(Actions.sms(this, call.argument("number"), call.argument("text")));
                            break;
'''
open(p, "w").write(t.replace(marker, new + marker))

# 2) Home screen: rebuild when the pipeline status changes
p = S + "/lib/screens/home_screen.dart"
t = open(p).read()
marker = "  final p = VoicePipeline.i;\n"
assert t.count(marker) == 1, "home marker"
add = '''  final p = VoicePipeline.i;

  @override
  void initState() {
    super.initState();
    p.status.addListener(_refresh);
  }

  void _refresh() {
    if (mounted) setState(() {});
  }

  @override
  void dispose() {
    p.status.removeListener(_refresh);
    super.dispose();
  }
'''
open(p, "w").write(t.replace(marker, add))
PY

# 3) Manifest: CALL_PHONE permission + let the app see launcher apps
M=$S/android_overlay/app/src/main/AndroidManifest.xml
sed -i 's#<uses-permission android:name="android.permission.INTERNET" />#<uses-permission android:name="android.permission.INTERNET" />\n    <uses-permission android:name="android.permission.CALL_PHONE" />#' $M
sed -i 's#<queries>#<queries>\n        <intent><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent>#' $M

# 4) Setup label
sed -i 's/Picovoice AccessKey (required)/Not needed - leave empty/' $S/lib/screens/setup_screen.dart
