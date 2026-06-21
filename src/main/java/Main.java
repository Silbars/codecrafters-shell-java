import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
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

    private static final Set<String> BUILTINS = new HashSet<>(
            Arrays.asList("exit", "echo", "type", "pwd", "cd", "jobs"));

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

            List<String> tokens = parsedQuotes(input);
            List<List<String>> pipeline = new ArrayList<>();
            List<String> currentCommand = new ArrayList<>();

            for (String token : tokens) {
                if (token.equals("|")) {
                    pipeline.add(currentCommand);
                    currentCommand = new ArrayList<>();
                } else {
                    currentCommand.add(token);
                }
            }
            if (!currentCommand.isEmpty()) {
                pipeline.add(currentCommand);
            }

            if (pipeline.size() > 1) {
                executePipeline(pipeline);
                continue;
            }

            String command = tokens.get(0);
            List<String> arguments = tokens.size() > 1 ? tokens.subList(1, tokens.size()) : new ArrayList<>();
            handleCommand(command, arguments);
        }
    }

    private static void handleCommand(String command, List<String> arguments) {
        Redirection redirection = extractRedirection(arguments);
        String argstr = String.join(" ", arguments);
        boolean isBackground = checkBackground(arguments);
        switch (command) {
            case "exit":
                System.exit(0);
                break;
            case "echo":
                handleEcho(argstr, redirection);
                break;
            case "type":
                handleType(argstr);
                break;
            case "pwd":
                System.out.println(currentDirectory.toAbsolutePath());
                break;
            case "cd":
                handleCd(argstr);
                break;
            case "jobs":
                handleJobs();
                break;
            default:
                String executablePath = findExecutable(command);

                if (executablePath == null) {
                    System.out.println(command + ": command not found");
                } else {
                    executeProgram(command, arguments, redirection, isBackground);
                }
                break;
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
                } catch (IOException e) {
                }
            }
        } else {
            System.out.println(arguments);
        }
    }

    private static String getBuiltinOutput(String command, List<String> args) {
        String argstr = String.join(" ", args);
        switch (command) {
            case "echo":
                return argstr + "\n";
            case "pwd":
                return currentDirectory.toAbsolutePath() + "\n";
            case "type":
                if (BUILTINS.contains(argstr)) {
                    return argstr + " is a shell builtin\n";
                } else {
                    String path = findExecutable(argstr);
                    if (path == null) {
                        return argstr + ": not found\n";
                    } else {
                        return argstr + " is " + path + "\n";
                    }
                }
            case "cd":
                handleCd(argstr);
                return "";
            case "exit":
                System.exit(0);
                return "";
            default:
                return "";
        }
    }

    private static void executePipeline(List<List<String>> pipeline) {
        if (pipeline.isEmpty())
            return;

        // Check if any command is a builtin
        boolean hasBuiltin = false;
        for (List<String> cmd : pipeline) {
            if (!cmd.isEmpty() && BUILTINS.contains(cmd.get(0))) {
                hasBuiltin = true;
                break;
            }
        }

        // All external — use startPipeline (your existing fast path)
        if (!hasBuiltin) {
            executeExternalPipeline(pipeline);
            return;
        }

        // Mixed pipeline: builtins + externals
        try {
            List<Process> processes = new ArrayList<>();
            List<Thread> threads = new ArrayList<>();
            InputStream previousOutput = null;

            for (int i = 0; i < pipeline.size(); i++) {
                List<String> parts = new ArrayList<>(pipeline.get(i));
                boolean isLast = (i == pipeline.size() - 1);
                String cmd = parts.get(0);
                List<String> args = parts.size() > 1
                        ? new ArrayList<>(parts.subList(1, parts.size()))
                        : new ArrayList<>();

                Redirection redirection = isLast ? extractRedirection(args) : null;

                if (BUILTINS.contains(cmd)) {
                    // Capture builtin output
                    String output = getBuiltinOutput(cmd, args);

                    if (isLast) {
                        if (redirection != null) {
                            writeRedirected(output, redirection);
                        } else {
                            System.out.print(output);
                            System.out.flush();
                        }
                    } else {
                        previousOutput = new java.io.ByteArrayInputStream(output.getBytes());
                    }
                } else {
                    // External command
                    List<String> commandList = new ArrayList<>();
                    commandList.add(cmd);
                    commandList.addAll(args);

                    ProcessBuilder pb = new ProcessBuilder(commandList);
                    pb.directory(currentDirectory.toFile());
                    pb.redirectError(ProcessBuilder.Redirect.INHERIT);

                    if (isLast) {
                        if (redirection != null) {
                            File f = new File(redirection.file);
                            switch (redirection.type) {
                                case ">":
                                case "1>":
                                    pb.redirectOutput(f);
                                    break;
                                case ">>":
                                case "1>>":
                                    pb.redirectOutput(ProcessBuilder.Redirect.appendTo(f));
                                    break;
                                case "2>":
                                    pb.redirectError(f);
                                    pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                                    break;
                                case "2>>":
                                    pb.redirectError(ProcessBuilder.Redirect.appendTo(f));
                                    pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                                    break;
                            }
                        } else {
                            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                        }
                    }

                    Process process = pb.start();
                    processes.add(process);

                    // Feed previous output into this process's stdin
                    if (previousOutput != null) {
                        InputStream prevOut = previousOutput;
                        Thread feeder = new Thread(() -> {
                            try {
                                prevOut.transferTo(process.getOutputStream());
                                process.getOutputStream().close();
                            } catch (IOException e) {
                            }
                        });
                        feeder.start();
                        threads.add(feeder);
                        previousOutput = null;
                    }

                    if (!isLast) {
                        previousOutput = process.getInputStream();
                    }
                }
            }

            for (Process p : processes)
                p.waitFor();
            for (Thread t : threads)
                t.join();

        } catch (Exception e) {
            System.out.println("Error in pipeline: " + e.getMessage());
        }
    }

    private static void writeRedirected(String output, Redirection redirection) {
        try {
            if (redirection.type.equals(">") || redirection.type.equals("1>")) {
                Files.writeString(Path.of(redirection.file), output);
            } else if (redirection.type.equals(">>") || redirection.type.equals("1>>")) {
                try (FileWriter fw = new FileWriter(redirection.file, true)) {
                    fw.write(output);
                }
            } else if (redirection.type.equals("2>") || redirection.type.equals("2>>")) {
                System.out.print(output);
                System.out.flush();
            }
        } catch (IOException e) {
            System.out.println("Error writing to file: " + e.getMessage());
        }
    }

    private static void executeExternalPipeline(List<List<String>> pipeline) {
        if (pipeline.isEmpty())
            return;

        try {
            List<ProcessBuilder> builders = new ArrayList<>();
            boolean isBackground = false;

            for (int i = 0; i < pipeline.size(); i++) {
                boolean isFirst = (i == 0);
                boolean isLast = (i == pipeline.size() - 1);

                List<String> tokens = new ArrayList<>(pipeline.get(i));
                if (tokens.isEmpty())
                    continue;

                String cmd = tokens.get(0);
                List<String> args = tokens.subList(1, tokens.size()); // backed view — edits affect tokens

                Redirection redir = null;
                if (isLast) {
                    redir = extractRedirection(args); // strips "> file" tokens from args in-place
                    isBackground = checkBackground(args); // strips trailing & from args in-place
                }

                List<String> cmdList = new ArrayList<>();
                cmdList.add(cmd);
                cmdList.addAll(args); // args is now clean after redirection/& removal

                ProcessBuilder pb = new ProcessBuilder(cmdList);
                pb.directory(currentDirectory.toFile());

                if (isFirst)
                    pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
                pb.redirectError(ProcessBuilder.Redirect.INHERIT); // stderr → terminal for all

                if (isLast) {
                    pb.redirectOutput(ProcessBuilder.Redirect.INHERIT); // default
                    if (redir != null) {
                        File f = new File(redir.file);
                        switch (redir.type) {
                            case ">":
                            case "1>":
                                pb.redirectOutput(f);
                                break;
                            case ">>":
                            case "1>>":
                                pb.redirectOutput(ProcessBuilder.Redirect.appendTo(f));
                                break;
                            case "2>":
                                pb.redirectError(f);
                                break;
                            case "2>>":
                                pb.redirectError(ProcessBuilder.Redirect.appendTo(f));
                                break;
                        }
                    }
                }

                builders.add(pb);
            }

            if (builders.isEmpty())
                return;

            // Automatically wires stdout[i] → stdin[i+1]
            List<Process> processes = ProcessBuilder.startPipeline(builders);

            if (!isBackground) {
                for (Process p : processes)
                    p.waitFor();
            } else {
                Process last = processes.get(processes.size() - 1);
                int jobId = 1;
                while (backgroundJobs.containsKey(jobId))
                    jobId++;

                StringBuilder fullCmd = new StringBuilder();
                for (int i = 0; i < pipeline.size(); i++) {
                    if (i > 0)
                        fullCmd.append(" | ");
                    fullCmd.append(String.join(" ", pipeline.get(i)));
                }
                System.out.println("[" + jobId + "] " + last.pid());
                backgroundJobs.put(jobId, new Job(last, fullCmd.toString()));
            }

        } catch (Exception e) {
            System.out.println("Error in pipeline: " + e.getMessage());
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
                System.out.println("[" + jobId + "]" + statusChar + " Running " + "     " + job.command);
            } else {
                System.out.println("[" + jobId + "]" + statusChar + " Done " + "        " + job.command);
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
                if (i < arguments.size() - 1) {
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

    private static void executeProgram(String command, List<String> arguments, Redirection redirection,
            boolean isBackground) {
        try {
            List<String> commandList = new ArrayList<>();
            commandList.add(command);

            if (!arguments.isEmpty()) {
                commandList.addAll(arguments);
            }

            ProcessBuilder pb = new ProcessBuilder(commandList);
            pb.directory(currentDirectory.toFile());
            pb.inheritIO();
            if (redirection != null) {
                ProcessBuilder.Redirect appendRedirect = ProcessBuilder.Redirect.appendTo(new File(redirection.file));
                if (redirection.type.equals(">") || redirection.type.equals("1>")) {
                    pb.redirectOutput(new File(redirection.file));
                } else if (redirection.type.equals("2>")) {
                    pb.redirectError(new File(redirection.file));
                } else if (redirection.type.equals("1>>") || redirection.type.equals(">>")) {
                    pb.redirectOutput(appendRedirect);
                } else if (redirection.type.equals("2>>")) {
                    pb.redirectError(appendRedirect);
                }
            }
            Process process = pb.start();
            if (!isBackground) {
                process.waitFor();
            } else {
                int jobId = 1;
                while (backgroundJobs.containsKey(jobId))
                    jobId++;

                System.out.println("[" + jobId + "] " + process.pid());
                String fullCommand = command + (arguments.isEmpty() ? "" : " " + String.join(" ", arguments));
                backgroundJobs.put(jobId, new Job(process, fullCommand));
            }

        } catch (Exception e) {
            System.out.println("Error executing program: " + e.getMessage());
        }
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
        if (!args.isEmpty() && args.getLast().equals("&")) {
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
                System.out.println("[" + jobId + "]" + statusChar + " Done " + "        " + job.command);
                iterator.remove();
            }
        }
    }

    private static List<String> parsedQuotes(String input) {
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
                } else if (c == '|') {
                    if (current.length() > 0) {
                        args.add(current.toString());
                        current = new StringBuilder();
                    }
                    args.add("|");
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