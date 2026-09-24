import 'dart:convert';
import 'dart:io';

import 'package:collection/collection.dart';
import 'package:hiddify/core/preferences/preferences_provider.dart';
import 'package:hiddify/features/profile/data/profile_data_providers.dart';
import 'package:hiddify/features/profile/model/profile_entity.dart';
import 'package:hiddify/hiddifycore/generated/v2/hcore/hcore.pb.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:loggy/loggy.dart';

final _logger = Loggy('OfflineProxyLoader');

/// Extracts 2-letter ISO country code from emoji flag in tag if present.
/// Country flag emojis consist of two Regional Indicator Symbols (0x1F1E6 to 0x1F1FF).
String extractCountryCodeFromTag(String tag) {
  final runes = tag.runes.toList();
  for (var i = 0; i < runes.length - 1; i++) {
    final r1 = runes[i];
    final r2 = runes[i + 1];
    if (r1 >= 0x1F1E6 && r1 <= 0x1F1FF && r2 >= 0x1F1E6 && r2 <= 0x1F1FF) {
      final c1 = String.fromCharCode(r1 - 0x1F1E6 + 65);
      final c2 = String.fromCharCode(r2 - 0x1F1E6 + 65);
      return "$c1$c2".toLowerCase();
    }
  }
  return "";
}

/// Strips duplicate index suffix (e.g. " § 0") added by Hiddify config generator.
String trimTagName(String tag) {
  final idx = tag.indexOf('§');
  if (idx != -1) {
    return tag.substring(0, idx).trim();
  }
  return tag.trim();
}

/// Measures TCP connection latency to [host]:[port].
/// Returns elapsed time in milliseconds, or 65535 on timeout/failure.
Future<int> tcpPing(String host, int port, {Duration timeout = const Duration(seconds: 3)}) async {
  if (host.isEmpty || port <= 0) return 65535;

  var targetHost = host;
  var targetPort = port;

  // For loopback / olcRTC local SOCKS, ping the carrier endpoint
  if (targetHost == "127.0.0.1" || targetHost == "localhost") {
    targetHost = "my.mts-link.ru";
    targetPort = 443;
  }

  final sw = Stopwatch()..start();
  try {
    final socket = await Socket.connect(targetHost, targetPort, timeout: timeout);
    sw.stop();
    await socket.close();
    return sw.elapsedMilliseconds;
  } catch (_) {
    return 65535;
  }
}

/// Runs TCP pings across [items] concurrently in batches.
Future<Map<String, int>> pingOutboundGroupConcurrently(
  List<OutboundInfo> items, {
  int concurrency = 10,
  Duration timeout = const Duration(seconds: 3),
  void Function(String tag, int delay)? onProgress,
}) async {
  final Map<String, int> delays = {};

  for (var i = 0; i < items.length; i += concurrency) {
    final end = (i + concurrency < items.length) ? i + concurrency : items.length;
    final chunk = items.sublist(i, end);

    await Future.wait(
      chunk.map((item) async {
        final delay = await tcpPing(item.host, item.port, timeout: timeout);
        delays[item.tag] = delay;
        item.urlTestDelay = delay;
        onProgress?.call(item.tag, delay);
      }),
    );
  }

  return delays;
}

/// Loads the offline OutboundGroup for the given [profile] from its JSON config file.
Future<OutboundGroup?> loadOfflineOutboundGroup(
  Ref ref,
  ProfileEntity profile,
  Map<String, int> offlineDelays,
) async {
  try {
    final profilePathResolver = ref.read(profilePathResolverProvider);
    final file = profilePathResolver.file(profile.id);
    if (!await file.exists()) {
      _logger.debug("Profile config file does not exist: ${file.path}");
      return null;
    }

    final content = await file.readAsString();
    if (content.isEmpty) return null;

    final json = jsonDecode(content);
    if (json is! Map<String, dynamic>) return null;

    final rawOutbounds = json['outbounds'] as List<dynamic>? ?? [];

    // Find selector group if present
    Map<String, dynamic>? selector;
    for (final o in rawOutbounds) {
      if (o is Map<String, dynamic> && o['type'] == 'selector') {
        selector = o;
        break;
      }
    }

    final List<String> selectorOutboundTags = (selector?['outbounds'] as List<dynamic>?)
            ?.map((e) => e.toString())
            .toList() ??
        [];

    final defaultTag = selector?['default']?.toString() ?? "";

    // Map all raw outbounds by tag
    final Map<String, Map<String, dynamic>> outboundsByTag = {};
    for (final o in rawOutbounds) {
      if (o is Map<String, dynamic>) {
        final tag = o['tag']?.toString();
        if (tag != null) {
          outboundsByTag[tag] = o;
        }
      }
    }

    bool isExcluded(String tag, String type) {
      if (tag.contains("§hide§")) return true;
      final lowerTag = tag.toLowerCase();
      final lowerType = type.toLowerCase();
      if (['direct', 'bypass', 'block', 'dns', 'dns-out', 'direct-fragment'].contains(lowerTag)) {
        return true;
      }
      if (['selector', 'urltest', 'dns', 'block', 'direct'].contains(lowerType)) {
        return true;
      }
      return false;
    }

    final List<Map<String, dynamic>> proxyConfigs = [];

    if (selectorOutboundTags.isNotEmpty) {
      for (final tag in selectorOutboundTags) {
        final conf = outboundsByTag[tag];
        final type = conf?['type']?.toString() ?? '';
        if (!isExcluded(tag, type)) {
          if (conf != null) {
            proxyConfigs.add(conf);
          } else {
            proxyConfigs.add({'tag': tag, 'type': 'proxy'});
          }
        }
      }
    } else {
      for (final o in rawOutbounds) {
        if (o is Map<String, dynamic>) {
          final tag = o['tag']?.toString() ?? '';
          final type = o['type']?.toString() ?? '';
          if (!isExcluded(tag, type)) {
            proxyConfigs.add(o);
          }
        }
      }
    }

    if (proxyConfigs.isEmpty) return null;

    // Load saved selected proxy from SharedPreferences
    final prefs = await ref.read(sharedPreferencesProvider.future);
    final savedSelected = prefs.getString("selected_proxy_${profile.id}");

    String selectedTag = "";
    if (savedSelected != null && proxyConfigs.any((e) => e['tag'] == savedSelected)) {
      selectedTag = savedSelected;
    } else if (defaultTag.isNotEmpty && proxyConfigs.any((e) => e['tag'] == defaultTag)) {
      selectedTag = defaultTag;
    } else {
      selectedTag = proxyConfigs.first['tag']?.toString() ?? "";
    }

    final items = <OutboundInfo>[];
    for (final conf in proxyConfigs) {
      final tag = conf['tag']?.toString() ?? '';
      final type = conf['type']?.toString() ?? 'unknown';

      // Detect host and port
      String host = conf['server']?.toString() ?? '';
      int port = 0;
      final rawPort = conf['server_port'];
      if (rawPort is int) {
        port = rawPort;
      } else if (rawPort is String) {
        port = int.tryParse(rawPort) ?? 0;
      }

      // Wireguard peers
      if (host.isEmpty && conf['peers'] is List && (conf['peers'] as List).isNotEmpty) {
        final peer = (conf['peers'] as List).first;
        if (peer is Map<String, dynamic>) {
          host = peer['server']?.toString() ?? '';
          final p = peer['server_port'];
          port = p is int ? p : (int.tryParse(p?.toString() ?? '') ?? 0);
        }
      }

      final delay = offlineDelays[tag] ?? 0;
      var countryCode = extractCountryCodeFromTag(tag);
      if (countryCode.isEmpty) {
        countryCode = prefs.getString("proxy_country_${profile.id}_$tag") ?? "";
      }
      final isSelected = (tag == selectedTag);

      items.add(
        OutboundInfo(
          tag: tag,
          tagDisplay: trimTagName(tag),
          type: type,
          host: host,
          port: port,
          isVisible: true,
          isSelected: isSelected,
          urlTestDelay: delay,
          ipinfo: IpInfo(
            countryCode: countryCode,
          ),
        ),
      );
    }

    final groupTag = selector?['tag']?.toString() ?? "select";
    return OutboundGroup(
      tag: groupTag,
      type: "selector",
      selected: selectedTag,
      selectable: true,
      isExpand: true,
      items: items,
    );
  } catch (e, st) {
    _logger.error("Error loading offline outbound group", e, st);
    return null;
  }
}
