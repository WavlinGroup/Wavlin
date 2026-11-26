// Parameters the Android host sends to the embedded reverb visualizer.
// Pure parsing so it can be tested without a screen or a platform channel.

import 'dart:ui' show Color;

class ReverbParams {
  const ReverbParams({
    required this.presetIndex,
    required this.enabled,
    required this.speed,
    this.primary,
    this.secondary,
    this.tertiary,
    this.muted,
  });

  final int presetIndex;
  final bool enabled;
  final double speed;
  final Color? primary;
  final Color? secondary;
  final Color? tertiary;
  final Color? muted;

  static const idle = ReverbParams(presetIndex: 0, enabled: false, speed: 1.0);

  /// Returns null when [raw] is not a map or a required field has the wrong type.
  /// Colors are ARGB ints as produced by Compose's Color.toArgb().
  static ReverbParams? tryParse(Object? raw) {
    if (raw is! Map) return null;
    final preset = raw['presetIndex'];
    final enabled = raw['enabled'];
    final speed = raw['speed'];
    if (preset is! int || enabled is! bool || speed is! num) return null;
    final speedValue = speed.toDouble();
    if (!speedValue.isFinite) return null;
    return ReverbParams(
      presetIndex: preset,
      enabled: enabled,
      speed: speedValue,
      primary: _color(raw['primary']),
      secondary: _color(raw['secondary']),
      tertiary: _color(raw['tertiary']),
      muted: _color(raw['muted']),
    );
  }

  static Color? _color(Object? value) => value is int ? Color(value & 0xFFFFFFFF) : null;
}
