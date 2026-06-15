import java.util.Scanner;

public class Main {
    public static void main(String[] args) throws Exception {
        Scanner sc = new Scanner(System.in);
        while (true) { 
            System.out.print("$ ");
            String command = sc.nextLine().trim();
            String[] commandWords = command.split(" ", 2);
            String builtinCommands = "echo exit type";

            if (command.equals("exit")) break;
            else if(commandWords[0].equals("echo")) {
                String newCommand = commandWords.length > 1 ? commandWords[1] : "";
                System.out.println(newCommand);
            } else if (commandWords[0].equals("type")) {
                if(builtinCommands.contains(commandWords[1])) {
                    System.out.println(commandWords[1] +" is a shell builtin");
                } else {
                    System.out.println(commandWords[1] + ": not found");
                }
            } else {
                System.out.println(command + ": command not found");    
            }
        }
        sc.close();
    }
}
