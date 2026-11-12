package io.plainround.seatwave.files;

import java.nio.file.Path;

/** The one place the lab programs turn settings problems into messages and an exit status. */
final class LabSettings {

  // The usual status for a usage or configuration error.
  private static final int SETTINGS_PROBLEM = 2;

  private LabSettings() {}

  /** The settings, or every problem printed and the process stopped with status 2. */
  static StoreSettings loadOrExit(Path projectFolder) {
    return StoreSettings.load(projectFolder)
        .peekError(problems -> {
          problems.forEach(problem -> System.err.println("settings: " + problem));
          System.exit(SETTINGS_PROBLEM);
        })
        .discardError()
        .orElseThrow();
  }
}
