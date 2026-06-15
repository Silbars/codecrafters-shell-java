import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
                System.out.println(command + ": command not found");
                break;
        }
    }

    private static void handleType(String input) {
        if (BUILTINS.contains(input)) {
            System.out.println(input + " is a shell builtin");
        } else {
            System.out.println(handlePath(input));
        }
    }

    private static String handlePath(String input) {
        String pathEnv = System.getenv("PATH");
        String[] pathDirectories = pathEnv.split(File.pathSeparator);

        String result = input + ": not found";

        for (String directory : pathDirectories) {
            Path path = Paths.get(directory, input);

            if(Files.isExecutable(path) && !Files.isDirectory(path)) {
                result = (input + " is " + path);
                return result;
            }
        }
        return result;
    }
}
