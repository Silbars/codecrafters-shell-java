import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Scanner;
import java.util.Set;

public class Main {
    private static final Set<String> BUILTINS = new HashSet<>(Arrays.asList("exit", "echo", "type", "pwd"));

    private static Path currentDirectory = Paths.get(System.getProperty("user.dir"));

    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        while (true) { 
            System.out.print("$ ");
            String input = sc.nextLine().trim();

            if (input.isEmpty()) continue;

            String[] inputParts = input.split(" ", 2);
            String command = inputParts[0];
            String arguments = inputParts.length > 1 ? inputParts[1] : "";
            
            handleCommand(command, arguments, input);
        }
    }

    private static void handleCommand(String command, String arguments, String input) {
        switch (command) {
            case "exit":
                System.exit(0);
                break;
            case "echo":
                List<String> parsedArgs = parseArguments(arguments);
                System.out.println(String.join(" ", parsedArgs));
                break;
            case "type":
                handleType(arguments);
                break;
            case "pwd":
                System.out.println(currentDirectory.toAbsolutePath());
                break;
            case "cd":
                handleCd(arguments);
                break;
            default:
                String executablePath = findExecutable(command);

                if (executablePath == null) {
                    System.out.println(command + ": command not found");
                } else {
                    executeProgram(command, arguments);
                }
                break;
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

    private static String findExecutable(String input) {
        String pathEnv = System.getenv("PATH");
        
        if (pathEnv == null) return null;

        String[] pathDirectories = pathEnv.split(File.pathSeparator);

        for (String directory : pathDirectories) {
            Path path = Paths.get(directory, input);

            if (Files.isExecutable(path) && !Files.isDirectory(path)) {
                return path.toAbsolutePath().toString();
            }
        }
        return null;
    }

    private static void executeProgram(String command, String arguments) {
        try {
            List<String> commandList = new ArrayList<>();
            commandList.add(command);

            if (!arguments.isEmpty()) {
                commandList.addAll(parseArguments(arguments));
            }

            ProcessBuilder pb = new ProcessBuilder(commandList);
            pb.directory(currentDirectory.toFile());
            pb.inheritIO();
            Process process = pb.start();
            process.waitFor();
            
        } catch (Exception e) {
            System.out.println("Error executing program: " + e.getMessage());
        }
    }

    private static void handleCd(String pathArg) {
        if (pathArg.equals("~") || pathArg.startsWith("~/")) {
            String homeDir = System.getenv("HOME");
            if(homeDir == null) return;
            pathArg = pathArg.replaceFirst("^~", homeDir);
        }

        Path targetPath = currentDirectory.resolve(pathArg).normalize();

        if(Files.exists(targetPath) && Files.isDirectory(targetPath)) {
            currentDirectory = targetPath.toAbsolutePath();
        } else {
            System.out.println("cd: " + pathArg + ": No such file or directory");
        }
    }

    private static List<String> parseArguments(String arguments) {
        List<String> args = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuotes = false;
        boolean inDoubleQuotes = false;

        for (int i = 0; i < arguments.length(); i++) {
            char c = arguments.charAt(i);
             
            if (inSingleQuotes) {
                if (c == '\'') inSingleQuotes = false;
                else current.append(c);
            } else if (inDoubleQuotes) {
                if (c == '\"') inDoubleQuotes = false;
                else current.append(c);
            } else {
                if (c == '\'') inSingleQuotes = true;
                else if (c == '\"') inDoubleQuotes = true;
                else if (c == ' ') {
                    if (current.length() > 0) {
                        args.add(current.toString());
                        current = new StringBuilder();
                    }
                } else current.append(c);
            }
        }
        if (current.length() > 0) {
            args.add(current.toString());
        }
        return args;
    }
}