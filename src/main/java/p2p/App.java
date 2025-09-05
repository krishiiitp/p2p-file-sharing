package p2p;

public class App {
    public static void main(String[] args) {
        try {
            FileController fileController = new FileController(8080);
            fileController.start();
            System.out.println("SafeShare server started on port 8080");
            System.out.println("UI available at http://localhost:3000");
            Runtime.getRuntime().addShutdownHook(
                    new Thread(
                            () -> {
                                System.out.println("shutting down the server");
                                fileController.stop();
                            }
                    )
            );
            System.out.println("press enter to stop the server");
            // TODO: implement stopping of server when enter is pressed
        } catch (Exception exception) {
            System.err.println("failed to start the server at port 8080");
            exception.printStackTrace();
        }
    }
}
