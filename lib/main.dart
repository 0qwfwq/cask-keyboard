import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() {
  runApp(const CaskApp());
}

class CaskApp extends StatelessWidget {
  const CaskApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Cask Keyboard',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xFF4C8BF5)),
        useMaterial3: true,
      ),
      home: const SetupPage(),
    );
  }
}

class SetupPage extends StatefulWidget {
  const SetupPage({super.key});

  @override
  State<SetupPage> createState() => _SetupPageState();
}

class _SetupPageState extends State<SetupPage> with WidgetsBindingObserver {
  /// Must match the channel name registered in MainActivity.kt.
  static const _channel = MethodChannel('com.example.cask/keyboard');

  bool _enabled = false;
  bool _selected = false;

  bool _hapticsEnabled = true;
  double _hapticsStrength = 40;

  final TextEditingController _corpusController = TextEditingController();
  bool _ingesting = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _refreshStatus();
    _loadHaptics();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _corpusController.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // Re-check after the user returns from system settings / the picker.
    if (state == AppLifecycleState.resumed) _refreshStatus();
  }

  Future<void> _refreshStatus() async {
    try {
      final enabled = await _channel.invokeMethod<bool>('isKeyboardEnabled');
      final selected = await _channel.invokeMethod<bool>('isKeyboardSelected');
      if (!mounted) return;
      setState(() {
        _enabled = enabled ?? false;
        _selected = selected ?? false;
      });
    } on PlatformException {
      // Keep last known state on failure.
    }
  }

  Future<void> _openImeSettings() => _channel.invokeMethod('openImeSettings');

  Future<void> _showImePicker() => _channel.invokeMethod('showImePicker');

  Future<void> _loadHaptics() async {
    try {
      final res = await _channel.invokeMapMethod<String, dynamic>('getHaptics');
      if (res == null || !mounted) return;
      setState(() {
        _hapticsEnabled = res['enabled'] as bool? ?? true;
        _hapticsStrength = (res['strength'] as int? ?? 40).toDouble();
      });
    } on PlatformException {
      // Keep defaults on failure.
    }
  }

  /// Send the pasted text to the native side to fold into the on-device personal model.
  Future<void> _ingestCorpus() async {
    final text = _corpusController.text.trim();
    if (text.isEmpty || _ingesting) return;
    setState(() => _ingesting = true);
    try {
      final count = await _channel.invokeMethod<int>('ingestCorpus', {'text': text});
      if (!mounted) return;
      _corpusController.clear();
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            'Learned from ${count ?? 0} words. Switch keyboards and back (or reboot) to apply.',
          ),
        ),
      );
    } on PlatformException catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Could not import: ${e.message}')),
      );
    } finally {
      if (mounted) setState(() => _ingesting = false);
    }
  }

  Future<void> _saveHaptics() async {
    try {
      await _channel.invokeMethod('setHaptics', {
        'enabled': _hapticsEnabled,
        'strength': _hapticsStrength.round(),
      });
    } on PlatformException {
      // Ignore; the keyboard keeps its last saved setting.
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Cask Keyboard')),
      body: ListView(
        padding: const EdgeInsets.all(20),
        children: [
          _StatusCard(enabled: _enabled, selected: _selected),
          const SizedBox(height: 24),
          _StepCard(
            number: '1',
            title: 'Enable Cask Keyboard',
            subtitle: 'Turn on Cask in your device\'s on-screen keyboard list.',
            done: _enabled,
            buttonLabel: 'Open keyboard settings',
            onPressed: _openImeSettings,
          ),
          const SizedBox(height: 12),
          _StepCard(
            number: '2',
            title: 'Switch to Cask',
            subtitle: 'Pick Cask as your active keyboard.',
            done: _selected,
            buttonLabel: 'Choose keyboard',
            onPressed: _showImePicker,
          ),
          const SizedBox(height: 24),
          _HapticsCard(
            enabled: _hapticsEnabled,
            strength: _hapticsStrength,
            onEnabledChanged: (v) {
              setState(() => _hapticsEnabled = v);
              _saveHaptics();
            },
            onStrengthChanged: (v) => setState(() => _hapticsStrength = v),
            onStrengthSettled: (v) {
              setState(() => _hapticsStrength = v);
              HapticFeedback.selectionClick();
              _saveHaptics();
            },
          ),
          const SizedBox(height: 24),
          _CorpusCard(
            controller: _corpusController,
            busy: _ingesting,
            onSubmit: _ingestCorpus,
          ),
          const SizedBox(height: 24),
          const Text('Try it out', style: TextStyle(fontWeight: FontWeight.bold)),
          const SizedBox(height: 8),
          const TextField(
            maxLines: 3,
            decoration: InputDecoration(
              border: OutlineInputBorder(),
              hintText: 'Tap here and start typing…',
            ),
          ),
          const SizedBox(height: 16),
          Center(
            child: TextButton.icon(
              onPressed: _refreshStatus,
              icon: const Icon(Icons.refresh),
              label: const Text('Refresh status'),
            ),
          ),
        ],
      ),
    );
  }
}

class _StatusCard extends StatelessWidget {
  const _StatusCard({required this.enabled, required this.selected});

  final bool enabled;
  final bool selected;

  @override
  Widget build(BuildContext context) {
    final (text, color, icon) = switch ((enabled, selected)) {
      (true, true) => ('Cask is your active keyboard 🎉', Colors.green, Icons.check_circle),
      (true, false) => ('Enabled — now switch to Cask', Colors.orange, Icons.swap_horiz),
      _ => ('Not set up yet', Colors.red, Icons.info_outline),
    };
    return Card(
      color: color.withValues(alpha: 0.1),
      child: ListTile(
        leading: Icon(icon, color: color),
        title: Text(text, style: TextStyle(color: color, fontWeight: FontWeight.bold)),
      ),
    );
  }
}

class _HapticsCard extends StatelessWidget {
  const _HapticsCard({
    required this.enabled,
    required this.strength,
    required this.onEnabledChanged,
    required this.onStrengthChanged,
    required this.onStrengthSettled,
  });

  final bool enabled;
  final double strength;
  final ValueChanged<bool> onEnabledChanged;
  final ValueChanged<double> onStrengthChanged;
  final ValueChanged<double> onStrengthSettled;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Column(
        children: [
          SwitchListTile(
            secondary: const Icon(Icons.vibration),
            title: const Text('Haptic feedback'),
            subtitle: const Text('Vibrate on each key press'),
            value: enabled,
            onChanged: onEnabledChanged,
          ),
          ListTile(
            enabled: enabled,
            title: const Text('Strength'),
            subtitle: Slider(
              value: strength,
              min: 0,
              max: 100,
              divisions: 20,
              label: '${strength.round()}',
              onChanged: enabled ? onStrengthChanged : null,
              onChangeEnd: enabled ? onStrengthSettled : null,
            ),
          ),
        ],
      ),
    );
  }
}

class _CorpusCard extends StatelessWidget {
  const _CorpusCard({
    required this.controller,
    required this.busy,
    required this.onSubmit,
  });

  final TextEditingController controller;
  final bool busy;
  final VoidCallback onSubmit;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: const [
                Icon(Icons.school_outlined),
                SizedBox(width: 8),
                Expanded(
                  child: Text(
                    'Personalize from your writing',
                    style: TextStyle(fontWeight: FontWeight.bold, fontSize: 16),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 8),
            const Text(
              'Paste a chunk of your own text — messages, notes, emails. Cask learns your words and '
              'phrasing on-device to sharpen autocorrect and predictions. Nothing leaves your phone.',
            ),
            const SizedBox(height: 12),
            TextField(
              controller: controller,
              maxLines: 5,
              decoration: const InputDecoration(
                border: OutlineInputBorder(),
                hintText: 'Paste your text here…',
              ),
            ),
            const SizedBox(height: 12),
            Align(
              alignment: Alignment.centerRight,
              child: FilledButton.icon(
                onPressed: busy ? null : onSubmit,
                icon: busy
                    ? const SizedBox(
                        width: 16,
                        height: 16,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      )
                    : const Icon(Icons.auto_awesome),
                label: Text(busy ? 'Learning…' : 'Learn from this text'),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _StepCard extends StatelessWidget {
  const _StepCard({
    required this.number,
    required this.title,
    required this.subtitle,
    required this.done,
    required this.buttonLabel,
    required this.onPressed,
  });

  final String number;
  final String title;
  final String subtitle;
  final bool done;
  final String buttonLabel;
  final VoidCallback onPressed;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                CircleAvatar(
                  radius: 14,
                  backgroundColor: done ? Colors.green : Theme.of(context).colorScheme.primary,
                  child: done
                      ? const Icon(Icons.check, size: 16, color: Colors.white)
                      : Text(number, style: const TextStyle(color: Colors.white, fontSize: 14)),
                ),
                const SizedBox(width: 12),
                Expanded(child: Text(title, style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 16))),
              ],
            ),
            const SizedBox(height: 8),
            Text(subtitle),
            const SizedBox(height: 12),
            Align(
              alignment: Alignment.centerRight,
              child: FilledButton(onPressed: onPressed, child: Text(buttonLabel)),
            ),
          ],
        ),
      ),
    );
  }
}
