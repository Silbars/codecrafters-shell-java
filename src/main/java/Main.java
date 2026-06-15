import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Scanner;
import java.util.Set;

public class Main {
    private static final Set<String> BUILTINS = new HashSet<>(Arrays.asList("exit", "echo", "type"));
    public static void main(String[] args) throws Exception {
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
            case "exit" :
                System.exit(0);
                break;
            case "echo":
                System.out.println(arguments);
                break;
            case "type":
                handleType(arguments);
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
        String[] pathDirectories = pathEnv.split(File.pathSeparator);

        // String result = input + ": not found";

        for (String directory : pathDirectories) {
            Path path = Paths.get(directory, input);

            if(Files.isExecutable(path) && !Files.isDirectory(path)) {
                // result = (input + " is " + path);
                // return result;
                String pathString = path + "";
                return pathString;
            }
        }
        return null;
    }

    private static void executeProgram(String command, String arguments) {
        try {
            ArrayList<String> commandList = new ArrayList<>();
            commandList.add(command);

            if(!arguments.isEmpty()) {
                String[] argArr = arguments.split("\\s+");
                commandList.addAll(Arrays.asList(argArr));
            }

            ProcessBuilder pb = new ProcessBuilder(commandList);

            pb.inheritIO();
            Process process = pb.start();
            process.waitFor();
            
        } catch (Exception e) {
            System.out.println("Error executing program: " + e.getMessage());
        }
    }
}
