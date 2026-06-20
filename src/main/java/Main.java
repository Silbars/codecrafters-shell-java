import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;

public class Main {

    private static final Set<String> BUILTINS = new HashSet<>(Arrays.asList("exit", "echo", "type", "pwd", "cd", "jobs"));

    private static Path currentDirectory = Paths.get(System.getProperty("user.dir"));
    
    private static Map<Integer, Job> backgroundJobs = new LinkedHashMap<>();

    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        while (true) {
            checkBackgroundJobs();

            System.out.print("$ ");
            String input = sc.nextLine().trim();

            if (input.isEmpty()) {
                continue;
            }

            List<String> tokens = parser(input);
            List<List<String>> pipeline = new ArrayList<>();
            List<String> currentCommand = new ArrayList<>();
            
            for (String str : tokens) {
                if (!str.equals("|")) {
                    currentCommand.add(str);
                } else {
                    pipeline.add(currentCommand);
                    currentCommand = new ArrayList<>();
                }
            }
            
            if (!currentCommand.isEmpty()) {
                pipeline.add(currentCommand);
            }
            
            handleExecution(pipeline);
            checkBackgroundJobs();
        }
    }
    
    private static void handleEcho(String arguments, Redirection redirection) {
        if (redirection != null) {
            if (redirection.type.equals(">") || redirection.type.equals("1>")) {
                try {
                    Files.writeString(Path.of(redirection.file), arguments + "\n");
                } catch (IOException e) {
                    System.out.println("echo: permission denied or cannot write to file");
                }
            } else if (redirection.type.equals("2>")) {
                System.out.println(arguments);
                try {
                    Files.writeString(Path.of(redirection.file), "");
                } catch (IOException e) {
                    // Ignore
                }
            } else if (redirection.type.equals(">>") || redirection.type.equals("1>>")) {
                try (FileWriter fw = new FileWriter(redirection.file, true)) {
                    fw.write(arguments + "\n");
                } catch (IOException e) {
                    System.out.println("echo: permission denied or cannot write to file");
                }
            } else if (redirection.type.equals("2>>")) {
                System.out.println(arguments);

                try (FileWriter fw = new FileWriter(redirection.file, true)) {
                    fw.write("");
                } catch (IOException e) {}
            }
        } else {
            System.out.println(arguments);
        }
    }

    private static void handleType(String input) {
        if (BUILTINS.contains(input)) {
            System.out.println(input + " is a shell builtin");
        } else {
            String executablePath = findExecutable(input);
            if (executablePath == null) {
                System.out.println(input + ": not found");
            } else {
                System.out.println(input + " is " + executablePath);
            }
        }
    }

    private static void handleJobs() {

        List<Integer> activeIds = new ArrayList<>(backgroundJobs.keySet());
        
        int mostRecentId = -1;
        int secondMostRecentId = -1;
        
        if (!activeIds.isEmpty()) {
            mostRecentId = activeIds.get(activeIds.size() - 1);
        }
        if (activeIds.size() > 1) {
            secondMostRecentId = activeIds.get(activeIds.size() - 2);
        }

        var iterator = backgroundJobs.entrySet().iterator();
        
        while (iterator.hasNext()) {
            var entry = iterator.next();
            int jobId = entry.getKey();
            Job job = entry.getValue();

            char statusChar = ' ';
            if (jobId == mostRecentId) {
                statusChar = '+';
            } else if (jobId == secondMostRecentId) {
                statusChar = '-';
            }

            if (job.process.isAlive()) {
                System.out.printf("[%d]%c  Running               %s%n", jobId, statusChar, job.command);
            } else {
                System.out.printf("[%d]%c  Done                 %s%n", jobId, statusChar, job.command);
                iterator.remove(); 
            }
        }
    }

    private static Redirection extractRedirection(List<String> arguments) {

        for (int i = 0; i < arguments.size(); i++) {
            String str = arguments.get(i);

            if (str.equals(">") || 
                str.equals("1>") || 
                str.equals("2>") ||
                str.equals(">>") || 
                str.equals("1>>") ||
                str.equals("2>>")) {
                if ( i < arguments.size() - 1) {
                    String outputFile = arguments.get(i + 1);
                    arguments.subList(i, i + 2).clear();
                    return new Redirection(str, outputFile);
                } else if (i == arguments.size() - 1) {
                    System.out.println("syntax error: expected file name after redirection");
                    arguments.remove(i);
                    return null;
                }
            }
        }

        return null;
    }

    private static String findExecutable(String input) {
        String pathEnv = System.getenv("PATH");

        if (pathEnv == null) {
            return null;
        }

        String[] pathDirectories = pathEnv.split(File.pathSeparator);

        for (String directory : pathDirectories) {
            Path path = Paths.get(directory, input);

            if (Files.isExecutable(path) && !Files.isDirectory(path)) {
                return path.toAbsolutePath().toString();
            }
        }
        return null;
    }

    private static void handleCd(String pathArg) {
        if (pathArg.equals("~") || pathArg.startsWith("~/")) {
            String homeDir = System.getenv("HOME");
            if (homeDir == null) {
                return;
            }
            pathArg = pathArg.replaceFirst("^~", homeDir);
        }

        Path targetPath = currentDirectory.resolve(pathArg).normalize();

        if (Files.exists(targetPath) && Files.isDirectory(targetPath)) {
            currentDirectory = targetPath.toAbsolutePath();
        } else {
            System.out.println("cd: " + pathArg + ": No such file or directory");
        }
    }

    private static boolean checkBackground(List<String> args) {
        if(!args.isEmpty() && args.getLast().equals("&")) {
            args.removeLast();
            return true;
        }
        return false;
    }

    private static void checkBackgroundJobs() {
        List<Integer> activeIds = new ArrayList<>(backgroundJobs.keySet());
        
        int mostRecentId = -1;
        int secondMostRecentId = -1;
        
        if (!activeIds.isEmpty()) {
            mostRecentId = activeIds.get(activeIds.size() - 1);
        }
        if (activeIds.size() > 1) {
            secondMostRecentId = activeIds.get(activeIds.size() - 2);
        }

        var iterator = backgroundJobs.entrySet().iterator();
        
        while (iterator.hasNext()) {
            var entry = iterator.next();
            int jobId = entry.getKey();
            Job job = entry.getValue();

            char statusChar = ' ';
            if (jobId == mostRecentId) {
                statusChar = '+';
            } else if (jobId == secondMostRecentId) {
                statusChar = '-';
            }

            if (!job.process.isAlive()) {
                System.out.printf("[%d]%c  Done                 %s%n", jobId, statusChar, job.command);
                iterator.remove(); 
            }
        }
    }

    private static List<String> parser(String input) {
        List<String> args = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuotes = false;
        boolean inDoubleQuotes = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            if (inSingleQuotes) {
                if (c == '\'') {
                    inSingleQuotes = false;
                } else {
                    current.append(c);
                }
            } else if (inDoubleQuotes) {
                if (c == '\"') {
                    inDoubleQuotes = false;
                } else if (c == '\\' && i + 1 < input.length()) {
                    current.append(input.charAt(i + 1));
                    i++;
                } else {
                    current.append(c);
                }
            } else {
                if (c == '\\' && i + 1 < input.length()) {
                    current.append(input.charAt(i + 1));
                    i++;
                } else if (c == '\'') {
                    inSingleQuotes = true;
                } else if (c == '\"') {
                    inDoubleQuotes = true;
                } else if (c == ' ') {
                    if (current.length() > 0) {
                        args.add(current.toString());
                        current = new StringBuilder();
                    }
                } else {
                    current.append(c);
                }
            }
        }
        if (current.length() > 0) {
            args.add(current.toString());
        }
        return args;
    }

    private static ProcessBuilder createBuilder(String command, List<String> arguments, Redirection redirection) {
        List<String> fullCmd = new ArrayList<>();
        fullCmd.add(command);
        fullCmd.addAll(arguments);

        ProcessBuilder pb = new ProcessBuilder(fullCmd);
        pb.directory(currentDirectory.toFile());

        if (redirection != null) {
            ProcessBuilder.Redirect appendRedirect = ProcessBuilder.Redirect.appendTo(new File(redirection.file));
            if(redirection.type.equals(">") || redirection.type.equals("1>")) {
                pb.redirectOutput(new File(redirection.file));
            } else if (redirection.type.equals("2>")) {
                pb.redirectError(new File(redirection.file));
            } else if (redirection.type.equals("1>>") || redirection.type.equals(">>")) {
                pb.redirectOutput(appendRedirect);
            } else if (redirection.type.equals("2>>")) {
                pb.redirectError(appendRedirect);
            }
        }
        return pb;
    }

    private static void handleExecution(List<List<String>> pipeline) {
        if (pipeline.isEmpty()) return;

        if (pipeline.size() == 1) {
            List<String> cmdTokens = pipeline.get(0);
            String command = cmdTokens.get(0);
            
            if (BUILTINS.contains(command)) {
                List<String> arguments = cmdTokens.size() > 1 
                        ? new ArrayList<>(cmdTokens.subList(1, cmdTokens.size())) 
                        : new ArrayList<>();
                Redirection redirection = extractRedirection(arguments);
                
                switch (command) {
                    case "exit": System.exit(0); break;
                    case "echo": handleEcho(String.join(" ", arguments), redirection); break;
                    case "type": handleType(String.join(" ", arguments)); break;
                    case "pwd": System.out.println(currentDirectory.toAbsolutePath()); break;
                    case "cd": handleCd(String.join(" ", arguments)); break;
                    case "jobs": handleJobs(); break;
                }
                return; 
            }
        }

        try {
            List<String> lastCmdTokens = pipeline.get(pipeline.size() - 1);
            boolean isBackground = checkBackground(lastCmdTokens);

            List<ProcessBuilder> builders = new ArrayList<>();

            for (int i = 0; i < pipeline.size(); i++) {
                List<String> cmdTokens = pipeline.get(i);
                if (cmdTokens.isEmpty()) continue;

                String command = cmdTokens.get(0);
                
                if (findExecutable(command) == null) {
                    System.out.println(command + ": command not found");
                    return; 
                }

                List<String> arguments = cmdTokens.size() > 1 
                        ? new ArrayList<>(cmdTokens.subList(1, cmdTokens.size())) 
                        : new ArrayList<>();

                Redirection redirection = extractRedirection(arguments);
                ProcessBuilder pb = createBuilder(command, arguments, redirection);

                if (redirection == null) {
                    if (i == 0) pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
                    if (i == pipeline.size() - 1) pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                    pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                } else {
                    if (i == 0) pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
                    if (redirection.type.equals("2>") || redirection.type.equals("2>>")) {
                        if (i == pipeline.size() - 1) pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                    } else {
                        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                    }
                }
                
                builders.add(pb);
            }

            if (builders.isEmpty()) return;

            List<Process> processes = ProcessBuilder.startPipeline(builders);
            Process lastProcess = processes.get(processes.size() - 1);

            if (!isBackground) {
                lastProcess.waitFor();
            } else {
                List<String> fullPipelineStrings = new ArrayList<>();
                for (List<String> cmd : pipeline) {
                    fullPipelineStrings.add(String.join(" ", cmd));
                }
                String fullPipelineCommand = String.join(" | ", fullPipelineStrings);

                int jobId = 1;
                while (backgroundJobs.containsKey(jobId)) {
                    jobId++;
                }

                System.out.println("[" + jobId + "] " + lastProcess.pid());
                backgroundJobs.put(jobId, new Job(lastProcess, fullPipelineCommand));
            }

        } catch (Exception e) {
            System.out.println("Error executing command: " + e.getMessage());
        }
    }
}

class Redirection {
    public final String type;
    public final String file;
    public Redirection(String type, String file) {
        this.type = type;
        this.file = file;
    }
}

class Job {
    public final Process process;
    public final String command;

    public Job(Process process, String command) {
        this.process = process;
        this.command = command;
    }
}