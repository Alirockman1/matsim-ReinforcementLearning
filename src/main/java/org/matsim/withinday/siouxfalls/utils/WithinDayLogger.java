package org.matsim.withinday.siouxfalls.utils;

import java.io.*;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.utils.misc.Time;

public class WithinDayLogger {

    private final String logPath;
    private double lastTime = -1.0;
    private int lastIteration = -1;
    private boolean isClosed = false;

    public WithinDayLogger(String outputDirectory) {
        // Ensure path uses proper separators
        System.out.println("DEBUG: Logger is being initialized for path: " + outputDirectory);
        this.logPath = outputDirectory + File.separator + "withinday_log.xml";
        initXmlFile();
    }

private void initXmlFile() {
        try {
            File file = new File(logPath);
            if (file.getParentFile() != null) file.getParentFile().mkdirs();

            try (PrintWriter writer = new PrintWriter(new BufferedWriter(new FileWriter(logPath, false)))) {
                writer.println("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
                writer.println("<withinDayReplanningLog>");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // 2. Synchronized ensures Thread-Safety for Parallel Replanners
    public synchronized void logReplanningEvent(int iteration, double time, Id<Person> agentId, String mode) {
        if (isClosed) return;

        try (PrintWriter writer = new PrintWriter(new BufferedWriter(new FileWriter(logPath, true)))) {
            
            if (iteration != lastIteration) {
                if (lastIteration != -1) {
                    writer.println("    </simTime>");
                    writer.println("</iteration>");
                }
                writer.println("<iteration id=\"" + iteration + "\">");
                lastIteration = iteration;
                lastTime = -1.0; 
            }

            if (time != lastTime) {
                if (lastTime != -1.0) {
                    writer.println("    </simTime>");
                }
                writer.println("    <simTime seconds=\"" + time + "\" display=\"" + Time.writeTime(time) + "\">");
                lastTime = time;
            }

            writer.println("        <agent id=\"" + agentId + "\">");
            writer.println("            <mode>" + mode + "</mode>");
            writer.println("        </agent>");
            
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // 3. The Shutdown Hook: Closes the XML tags even if you stop the simulation manually
    private void setupShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            closeLog();
        }));
    }

    public synchronized void closeLog() {
        if (isClosed) return;
        try (PrintWriter writer = new PrintWriter(new BufferedWriter(new FileWriter(logPath, true)))) {
            if (lastIteration != -1) {
                writer.println("    </simTime>");
                writer.println("</iteration>");
            }
            writer.println("</withinDayReplanningLog>");
            isClosed = true;
            System.out.println("Successfully closed WithinDay Log.");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
