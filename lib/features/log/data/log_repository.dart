import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:fpdart/fpdart.dart';
import 'package:hiddify/core/utils/exception_handler.dart';
import 'package:hiddify/features/log/data/log_parser.dart';
import 'package:hiddify/features/log/data/log_path_resolver.dart';
import 'package:hiddify/features/log/model/log_entity.dart';
import 'package:hiddify/features/log/model/log_failure.dart';
import 'package:hiddify/hiddifycore/hiddify_core_service.dart';
import 'package:hiddify/utils/custom_loggers.dart';

abstract interface class LogRepository {
  TaskEither<LogFailure, Unit> init();
  Stream<Either<LogFailure, List<LogEntity>>> watchLogs();
  TaskEither<LogFailure, Unit> clearLogs();
}

class LogRepositoryImpl with ExceptionHandler, InfraLogger implements LogRepository {
  LogRepositoryImpl({required this.singbox, required this.logPathResolver});

  final HiddifyCoreService singbox;
  final LogPathResolver logPathResolver;

  @override
  TaskEither<LogFailure, Unit> init() {
    return exceptionHandler(() async {
      if (!kIsWeb) {
        if (!await logPathResolver.directory.exists()) {
          await logPathResolver.directory.create(recursive: true);
        }
        final coreFile = logPathResolver.coreFile();
        if (await coreFile.exists()) {
          try {
            await coreFile.copy("${coreFile.path}.prev");
          } catch (_) {}
          try {
            await coreFile.writeAsString(
              "\n\n==================== APP RESTARTED: ${DateTime.now().toIso8601String()} ====================\n\n",
              mode: FileMode.append,
            );
          } catch (_) {}
        } else {
          await coreFile.create(recursive: true);
        }

        final appFile = logPathResolver.appFile();
        if (await appFile.exists()) {
          try {
            await appFile.copy("${appFile.path}.prev");
          } catch (_) {}
          try {
            await appFile.writeAsString(
              "\n\n==================== APP RESTARTED: ${DateTime.now().toIso8601String()} ====================\n\n",
              mode: FileMode.append,
            );
          } catch (_) {}
        } else {
          await appFile.create(recursive: true);
        }

        final stderrFile = logPathResolver.stderrFile();
        if (await stderrFile.exists()) {
          try {
            final content = await stderrFile.readAsString();
            if (content.trim().isNotEmpty) {
              await appFile.writeAsString(
                "\n\n==================== STDERR (PANIC/CRASH) DETECTED ====================\n$content\n========================================================================\n\n",
                mode: FileMode.append,
              );
              await stderrFile.copy("${stderrFile.path}.prev");
            }
          } catch (_) {}
        }

        final crashFile = logPathResolver.crashFile();
        if (await crashFile.exists()) {
          try {
            final content = await crashFile.readAsString();
            if (content.trim().isNotEmpty) {
              await appFile.writeAsString(
                "\n\n==================== KOTLIN/JVM CRASH LOG DETECTED ====================\n$content\n=======================================================================\n\n",
                mode: FileMode.append,
              );
              await crashFile.copy("${crashFile.path}.prev");
            }
          } catch (_) {}
        }
      }
      return right(unit);
    }, LogUnexpectedFailure.new);
  }

  @override
  Stream<Either<LogFailure, List<LogEntity>>> watchLogs() {
    return singbox
        .watchLogs(logPathResolver.coreFile().path)
        .map((event) => event.map(LogParser.parseLogProto).toList())
        .handleExceptions((error, stackTrace) {
          loggy.warning("error watching logs", error, stackTrace);
          return LogFailure.unexpected(error, stackTrace);
        });
  }

  @override
  TaskEither<LogFailure, Unit> clearLogs() {
    return exceptionHandler(() => singbox.clearLogs().mapLeft(LogFailure.unexpected).run(), LogFailure.unexpected);
  }
}
