import 'dart:io';

import 'package:path/path.dart' as p;

class LogPathResolver {
  const LogPathResolver(this._workingDir);

  final Directory _workingDir;

  Directory get directory => _workingDir;

  File coreFile() {
    return File(p.join(directory.path, "box.log"));
  }

  File appFile() {
    return File(p.join(directory.path, "app.log"));
  }

  File stderrFile() {
    return File(p.join(directory.path, "stderr.log"));
  }

  File crashFile() {
    return File(p.join(directory.path, "crash.log"));
  }
}
