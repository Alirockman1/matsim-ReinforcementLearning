package org.matsim.withinday.siouxfalls.src.communication_networking;

import java.io.File;
import java.util.concurrent.Flow.Processor;

public class PythonService {

    public Process start() {
        try {
            System.out.println("Starting Python Uvicorn Service...");

            // Base location
            String projectRoot = System.getProperty("user.dir");

            // Location of the networking module
            File serverPath = new File(projectRoot, "src/main/python/networking");

            // The virtual environment path
            String virtualEnvironmentPython = new File(serverPath, ".myenv/Scripts/python.exe").getAbsolutePath();

            // code to be run in the background
            ProcessBuilder processBuilder = new ProcessBuilder(
                "cmd.exe", "/c", 
                "start", "cmd.exe", "/k",
                "\"" + virtualEnvironmentPython + "\" -m uvicorn Service1:app --host 127.0.0.1 --port 5000 --reload"
            );
            
            // Strat the service
            processBuilder.directory(serverPath);
            Process pythonProcess = processBuilder.start();
            
            // Give Python 5 seconds to initialize before MATSim hits the network
            Thread.sleep(5000); 
            System.out.println("Python Service should be live.");

            return pythonProcess;

        } catch (Exception e) {
            e.printStackTrace();

            return null;
        }
    }

    public void stop(Process pythonProcess) {

        if (pythonProcess != null) {
            System.out.println("Closing Python Service...");
            // On Windows, uvicorn spawns child processes, so we force kill the tree
            try {
                Runtime.getRuntime().exec("taskkill /F /T /PID " + pythonProcess.pid());

                Thread.sleep(1000);
            } catch (Exception e) {
                pythonProcess.destroyForcibly();
            }
        }
        
    }

}
