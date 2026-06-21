import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file. Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;

public class Main {

    private static final Set<String> BUILTINS = Set.of("exit", "echo", "type", "pwd", "cd", "jobs");

    private static Path currentDirectory = Paths.get(System.getProperty("user.dir"));
    private static final Map<Integer, Job> backgroundJobs = new LinkedHashMap<>();

    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        while (true) {
            checkBackgroundJobs();
            System.out.print("$ ");
            String input = sc.nextLine().trim();
            if (input.isEmpty()) continue;

            List<List<String>> pipeline = splitPipeline(parseTokens(input));

            if (pipeline.size() > 1) {
                executePipeline(pipeline);
            } else {
                List<String> tokens = pipeline.get(0);
                String command = tokens.get(0);
                List<String> arguments = tokens.size() > 1
                        ? new ArrayList<>(tokens.subList(1, tokens.size()))
                        : new ArrayList<>();
                handleCommand(command, arguments);
            }
        }
    }

    private static void handleCommand(String command, List<String> arguments) {
        Redirection redirection = extractRedirection(arguments);
        boolean isBackground = checkBackground(arguments);
        String argstr = String.join(" ", arguments);

        switch (command) {
            case "exit" -> System.exit(0);
            case "echo" -> writeOutput(argstr + "\n", redirection, true);
            case "type" -> handleType(argstr);
            case "pwd" -> System.out.println(currentDirectory.toAbsolutePath());
            case "cd" -> handleCd(argstr);
            case "jobs" -> handleJobs();
            default -> {
                String executablePath = findExecutable(command);
                if (executablePath == null) {
                    System.out.println(command + ": command not found");
                } else {
                    executeProgram(command, arguments, redirection, isBackground);
                }
            }
        }
    }

    // --- Redirection helpers (single source of truth) ---

    private static void writeOutput(String output, Redirection redirection, boolean isStdout) {
        if (redirection == null) {
            System.out.print(output);
            return;
        }

        boolean redirectsStdout = isStdoutRedirection(redirection.type());
        boolean redirectsStderr = isStderrRedirection(redirection.type());
        boolean append = isAppendRedirection(redirection.type());

        if ((isStdout && redirectsStdout) || (!isStdout && redirectsStderr)) {
            writeToFile(redirection.file(), output, append);
        } else {
            System.out.print(output);
            writeToFile(redirection.file(), "", append);
        }
    }

    private static void applyRedirection(ProcessBuilder pb, Redirection redirection) {
        if (redirection == null) return;
        File f = new File(redirection.file());
        boolean append = isAppendRedirection(redirection.type());

        if (isStdoutRedirection(redirection.type())) {
            pb.redirectOutput(append ? ProcessBuilder.Redirect.appendTo(f) : ProcessBuilder.Redirect.to(f));
        } else if (isStderrRedirection(redirection.type())) {
            pb.redirectError(append ? ProcessBuilder.Redirect.appendTo(f) : ProcessBuilder.Redirect.to(f));
            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        }
    }

    private static boolean isStdoutRedirection(String type) {
        return type.equals(">") || type.equals("1>") || type.equals(">>") || type.equals("1>>");
    }

    private static boolean isStderrRedirection(String type) {
        return type.equals("2>") || type.equals("2>>");
    }

    private static boolean isAppendRedirection(String type) {
        return type.equals(">>") || type.equals("1>>") || type.equals("2>>");
    }

    private static void writeToFile(String path, String content, boolean append) {
        try {
            if (append) {
                try (FileWriter fw = new FileWriter(path, true)) {
                    fw.write(content);
                }
            } else {
                Files.writeString(Path.of(path), content);
            }
        } catch (IOException e) {
            System.out.println("Error writing to file: " + e.getMessage());
        }
    }

    // --- Job helpers (single source of truth) ---

    private static int nextJobId() {
        int id = 1;
        while (backgroundJobs.containsKey(id)) id++;
        return id;
    }

    private static void registerBackgroundJob(Process process, String fullCommand) {
        int jobId = nextJobId();
        System.out.println("[" + jobId + "] " + process.pid());
        backgroundJobs.put(jobId, new Job(process, fullCommand));
    }

    private static char jobStatusChar(int jobId, List<Integer> activeIds) {
        int size = activeIds.size();
        if (size >= 1 && jobId == activeIds.get(size - 1)) return '+';
        if (size >= 2 && jobId == activeIds.get(size - 2)) return '-';
        return ' ';
    }

    private static String formatJob(int jobId, char statusChar, String status, String command) {
        String padding = status.equals("Running") ? "     " : "        ";
        return "[" + jobId + "]" + statusChar + " " + status + " " + padding + command;
    }

    private static void handleJobs() {
        List<Integer> activeIds = new ArrayList<>(backgroundJobs.keySet());
        var iterator = backgroundJobs.entrySet().iterator();

        while (iterator.hasNext()) {
            var entry = iterator.next();
            int jobId = entry.getKey();
            Job job = entry.getValue();
            char sc = jobStatusChar(jobId, activeIds);

            if (job.process().isAlive()) {
                System.out.println(formatJob(jobId, sc, "Running", job.command()));
            } else {
                System.out.println(formatJob(jobId, sc, "Done", job.command()));
                iterator.remove();
            }
        }
    }

    private static void checkBackgroundJobs() {
        List<Integer> activeIds = new ArrayList<>(backgroundJobs.keySet());
        var iterator = backgroundJobs.entrySet().iterator();

        while (iterator.hasNext()) {
            var entry = iterator.next();
            int jobId = entry.getKey();
            Job job = entry.getValue();

            if (!job.process().isAlive()) {
                char sc = jobStatusChar(jobId, activeIds);
                System.out.println(formatJob(jobId, sc, "Done", job.command()));
                iterator.remove();
            }
        }
    }

    // --- Builtin output (used by pipeline to capture builtin results) ---

    private static String getBuiltinOutput(String command, List<String> args) {
        String argstr = String.join(" ", args);
        return switch (command) {
            case "echo" -> argstr + "\n";
            case "pwd" -> currentDirectory.toAbsolutePath() + "\n";
            case "type" -> resolveType(argstr) + "\n";
            case "cd" -> { handleCd(argstr); yield ""; }
            case "exit" -> { System.exit(0); yield ""; }
            default -> "";
        };
    }

    private static String resolveType(String name) {
        if (BUILTINS.contains(name)) return name + " is a shell builtin";
        String path = findExecutable(name);
        return path != null ? name + " is " + path : name + ": not found";
    }

    private static void handleType(String input) {
        System.out.println(resolveType(input));
    }

    // --- Pipeline execution ---

    private static List<List<String>> splitPipeline(List<String> tokens) {
        List<List<String>> pipeline = new ArrayList<>();
        List<String> current = new ArrayList<>();

        for (String token : tokens) {
            if (token.equals("|")) {
                pipeline.add(current);
                current = new ArrayList<>();
            } else {
                current.add(token);
            }
        }
        if (!current.isEmpty()) pipeline.add(current);
        return pipeline;
    }

    private static void executePipeline(List<List<String>> pipeline) {
        if (pipeline.isEmpty()) return;

        boolean hasBuiltin = pipeline.stream()
                .anyMatch(cmd -> !cmd.isEmpty() && BUILTINS.contains(cmd.get(0)));

        if (!hasBuiltin) {
            executeExternalPipeline(pipeline);
            return;
        }

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
                    String output = getBuiltinOutput(cmd, args);
                    if (isLast) {
                        writeOutput(output, redirection, true);
                    } else {
                        previousOutput = new java.io.ByteArrayInputStream(output.getBytes());
                    }
                } else {
                    List<String> commandList = new ArrayList<>();
                    commandList.add(cmd);
                    commandList.addAll(args);

                    ProcessBuilder pb = new ProcessBuilder(commandList);
                    pb.directory(currentDirectory.toFile());
                    pb.redirectError(ProcessBuilder.Redirect.INHERIT);

                    if (isLast) {
                        pb.redirectOutput(ProcessBuilder.Redirect. INHERIT);
                        applyRedirection(pb, redirection);
                    }

                    Process process = pb.start();
                    processes.add(process);
                    previousOutput = feedPreviousOutput(previousOutput, process, threads);

                    if (!isLast) {
                        previousOutput = process.getInputStream();
                    }
                }
            }

            for (Process p : processes) p.waitFor();
            for (Thread t : threads) t.join();

        } catch (Exception e) {
            System.out.println("Error in pipeline: " + e.getMessage());
        }
    }

    private static InputStream feedPreviousOutput(InputStream previousOutput, Process process, List<Thread> threads) {
        if (previousOutput == null) return null;
        InputStream prevOut = previousOutput;
        Thread feeder = new Thread(() -> {
            try {
                prevOut.transferTo(process.getOutputStream());
                process.getOutputStream().close();
            } catch (IOException ignored) {}
        });
        feeder.start();
        threads.add(feeder);
        return null;
    }

    private static void executeExternalPipeline(List<List<String>> pipeline) {
        if (pipeline.isEmpty()) return;

        try {
            List<ProcessBuilder> builders = new ArrayList<>();
            boolean isBackground = false;

            for (int i = 0; i < pipeline.size(); i++) {
                boolean isFirst = (i == 0);
                boolean isLast = (i == pipeline.size() - 1);

                List<String> tokens = new ArrayList<>(pipeline.get(i));
                if (tokens.isEmpty()) continue;

                String cmd = tokens.get(0);
                List<String> args = tokens.subList(1, tokens.size());

                Redirection redir = null;
                if (isLast) {
                    redir = extractRedirection(args);
                    isBackground = checkBackground(args);
                }

                List<String> cmdList = new ArrayList<>();
                cmdList.add(cmd);
                cmdList.addAll(args);

                ProcessBuilder pb = new ProcessBuilder(cmdList);
                pb.directory(currentDirectory.toFile());
                if (isFirst) pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
                pb.redirectError(ProcessBuilder.Redirect.INHERIT);

                if (isLast) {
                    pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                    applyRedirection(pb, redir);
                }

                builders.add(pb);
            }

            if (builders.isEmpty()) return;

            List<Process> processes = ProcessBuilder.startPipeline(builders);

            if (!isBackground) {
                for (Process p : processes) p.waitFor();
            } else {
                Process last = processes.get(processes.size() - 1);
                StringBuilder fullCmd = new StringBuilder();
                for (int i = 0; i < pipeline.size(); i++) {
                    if (i > 0) fullCmd.append(" | ");
                    fullCmd.append(String.join(" ", pipeline.get(i)));
                }
                registerBackgroundJob(last, fullCmd.toString());
            }

        } catch (Exception e) {
            System.out.println("Error in pipeline: " + e.getMessage());
        }
    }

    // --- External program execution ---

    private static void executeProgram(String command, List<String> arguments, Redirection redirection,
            boolean isBackground) {
        try {
            List<String> commandList = new ArrayList<>();
            commandList.add(command);
            commandList.addAll(arguments);

            ProcessBuilder pb = new ProcessBuilder(commandList);
            pb.directory(currentDirectory.toFile());
            pb.inheritIO();
            applyRedirection(pb, redirection);

            Process process = pb.start();
            if (!isBackground) {
                process.waitFor();
            } else {
                String fullCommand = command + (arguments.isEmpty() ? "" : " " + String.join(" ", arguments));
                registerBackgroundJob(process, fullCommand);
            }

        } catch (Exception e) {
            System.out.println("Error executing program: " + e.getMessage());
        }
    }

    // --- cd ---

    private static void handleCd(String pathArg) {
        if (pathArg.equals("~") || pathArg.startsWith("~/")) {
            String homeDir = System.getenv("HOME");
            if (homeDir == null) return;
            pathArg = pathArg.replaceFirst("^~", homeDir);
        }

        Path targetPath = currentDirectory.resolve(pathArg).normalize();
        if (Files.exists(targetPath) && Files.isDirectory(targetPath)) {
            currentDirectory = targetPath.toAbsolutePath();
        } else {
            System.out.println("cd: " + pathArg + ": No such file or directory");
        }
    }

    // --- Token parsing ---

    private static Redirection extractRedirection(List<String> arguments) {
        for (int i = 0; i < arguments.size(); i++) {
            String str = arguments.get(i);
            if (isStdoutRedirection(str) || isStderrRedirection(str)) {
                if (i < arguments.size() - 1) {
                    String outputFile = arguments.get(i + 1);
                    arguments.subList(i, i + 2).clear();
                    return new Redirection(str, outputFile);
                } else {
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
        if (pathEnv == null) return null;

        for (String directory : pathEnv.split(File.pathSeparator)) {
            Path path = Paths.get(directory, input);
            if (Files.isExecutable(path) && !Files.isDirectory(path)) {
                return path.toAbsolutePath().toString();
            }
        }
        return null;
    }

    private static boolean checkBackground(List<String> args) {
        if (!args.isEmpty() && args.getLast().equals("&")) {
            args.removeLast();
            return true;
        }
        return false;
    }

    private static List<String> parseTokens(String input) {
        List<String> args = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuotes = false;
        boolean inDoubleQuotes = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            if (inSingleQuotes) {
                if (c == '\'') inSingleQuotes = false;
                else current.append(c);
            } else if (inDoubleQuotes) {
                if (c == '\"') {
                    inDoubleQuotes = false;
                } else if (c == '\\' && i + 1 < input.length()) {
                    current.append(input.charAt(++i));
                } else {
                    current.append(c);
                }
            } else {
                if (c == '\\' && i + 1 < input.length()) {
                    current.append(input.charAt(++i));
                } else if (c == '\'') {
                    inSingleQuotes = true;
                } else if (c == '\"') {
                    inDoubleQuotes = true;
                } else if (c == ' ') {
                    if (!current.isEmpty()) {
                        args.add(current.toString());
                        current = new StringBuilder();
                    }
                } else if (c == '|') {
                    if (!current.isEmpty()) {
                        args.add(current.toString());
                        current = new StringBuilder();
                    }
                    args.add("|");
                } else {
                    current.append(c);
                }
            }
        }
        if (!current.isEmpty()) args.add(current.toString());
        return args;
    }
}

record Redirection(String type, String file) {}

record Job(Process process, String command) {}