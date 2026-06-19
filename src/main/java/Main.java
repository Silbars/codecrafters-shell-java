import java.io.File;
import java.io.IOException;
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

    private static final Set<String> BUILTINS = new HashSet<>(Arrays.asList("exit", "echo", "type", "pwd", "cd"));

    private static Path currentDirectory = Paths.get(System.getProperty("user.dir"));

    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        while (true) {
            System.out.print("$ ");
            String input = sc.nextLine().trim();

            if (input.isEmpty()) {
                continue;
            }

            List<String> inputParts = parsedQuotes(input);
            String command = inputParts.get(0);
            List<String> arguments = inputParts.size() > 1 ? inputParts.subList(1, inputParts.size())
                    : new ArrayList<>();

            handleCommand(command, arguments);
        }
    }

    private static void handleCommand(String command, List<String> arguments) {
        Redirection redirection = extractRedirection(arguments);
        String argstr = String.join(" ", arguments);
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
            default:
                String executablePath = findExecutable(command);

                if (executablePath == null) {
                    System.out.println(command + ": command not found");
                } else {
                    executeProgram(command, arguments, redirection);
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

    private static Redirection extractRedirection(List<String> arguments) {

        for (int i = 0; i < arguments.size(); i++) {
            String str = arguments.get(i);

            if (str.equals(">") || str.equals("1>") || str.equals("2>")) {
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

    private static void executeProgram(String command, List<String> arguments, Redirection redirection) {
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
                if(redirection.type.equals(">") || redirection.type.equals("1>")) {
                    pb.redirectOutput(new File(redirection.file));
                } else if (redirection.type.equals("2>")) {
                    pb.redirectError(new File(redirection.file));
                }
            } 

            Process process = pb.start();
            process.waitFor();

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