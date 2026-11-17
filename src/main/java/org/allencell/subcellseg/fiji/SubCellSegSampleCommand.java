package org.allencell.subcellseg.fiji;

import ij.IJ;
import ij.ImagePlus;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import org.scijava.ItemIO;
import org.scijava.command.Command;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

/**
 * Opens a small test volume bundled with the plugin, so that a fresh installation
 * can be tried in one click: nuclear speckles (SON-mEGFP) in a hiPSC colony,
 * 40 x 256 x 256 voxels at 0.108 x 0.108 x 0.29 um, from the Allen Cell Collection.
 */
@Plugin(type = Command.class, menuPath = "File>Open Samples>SubCellSeg nuclear speckles (3D)", headless = true,
        description = "Open a small 3D test volume (nuclear speckles, SON-mEGFP) to try SubCellSeg on.")
public class SubCellSegSampleCommand implements Command {

    public static final String RESOURCE = "/sample/SubCellSeg_sample_SON.tif";
    public static final String TITLE = "SubCellSeg_sample_SON.tif";

    @Parameter
    private LogService log;

    @Parameter(type = ItemIO.OUTPUT)
    private ImagePlus sample;

    @Override
    public void run() {
        try {
            sample = open();
            if (GraphicsEnvironment.isHeadless()) {
                SubCellSegCommand.registerHeadless(sample);
            }
        } catch (final Exception e) {
            log.error("Could not open the SubCellSeg sample image", e);
            IJ.error("SubCellSeg", "Could not open the sample image: " + e.getMessage());
        }
    }

    /** Load the bundled sample volume into memory. */
    public static ImagePlus open() throws IOException {
        final Path tmp = Files.createTempFile("subcellseg_sample_", ".tif");
        try (InputStream in = SubCellSegSampleCommand.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IOException("sample image " + RESOURCE + " is missing from the plugin jar");
            }
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            final ImagePlus imp = IJ.openImage(tmp.toString());
            if (imp == null) {
                throw new IOException("ImageJ could not read the bundled sample image");
            }
            imp.setTitle(TITLE);
            return imp;
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
