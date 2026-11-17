// Forgets the "Install location" that the SubCellSeg Setup command stored in the ImageJ preferences.
//   fiji --headless --run test/clear_install_dir.groovy
ij.Prefs.set("subcellseg.install_dir", "")
ij.Prefs.savePreferences()
println("INSTALL_DIR_CLEARED")
