// Forget the dialog values that the SubCellSeg plugin remembers between runs
// (channel, output, threshold, ..., Python environment), so it starts from its defaults.
//
//   ImageJ-win64.exe --headless --console --run test/reset_settings.groovy
//
#@ PrefService prefs

import org.allencell.subcellseg.fiji.SubCellSegCommand

prefs.clear(SubCellSegCommand.class)
println("SETTINGS_RESET")
