package org.matsim.rl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.matsim.testcases.MatsimTestUtils;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class RunExternalModeChoiceTest {
    @RegisterExtension
    MatsimTestUtils utils = new MatsimTestUtils();

    @Test
    void testExternalModeChoice() {
        // This test would ideally run the RunExternalModeChoice main method and check for expected outcomes.
        // However, since it involves running a full MATSim simulation, we will just check that the main method can be called without exceptions.

        RunExternalModeChoice.main(new String[]{
                "--config:controller.outputDirectory="+ utils.getOutputDirectory(),
        });

        // assert that folder utils.getOutputDirectory() contains expected output files, e.g., "output_events.xml.gz"
        assertTrue(Path.of(utils.getOutputDirectory()).toFile().exists(), "Output directory should exist");
        assertTrue(Path.of(utils.getOutputDirectory(), "output_events.xml.zst").toFile().exists(), "Output events file should exist");
    }

}