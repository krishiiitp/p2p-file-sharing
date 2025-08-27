package p2p.service;

import p2p.utils.UploadUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;

public class FileSharer {
    private HashMap<Integer, String> availableFiles;

    public FileSharer() {
        availableFiles = new HashMap<>();
    }

    public int offerFile(String filePath) {
        int port;
        while (true) {
            port = UploadUtils.generateCode();
            if (!availableFiles.containsKey(port)) {
                availableFiles.put(port, filePath);
                return port;
            }
        }
    }

    public void startFileServer(int port) {
        String filePath = availableFiles.get(port);
        if (filePath == null) {
            System.out.println("no file is associated with port: " + port);
            return;
        }

        try(ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("serving file " + new File(filePath).getName() + "on port " + port);
            Socket clientSocket = serverSocket.accept();
            System.out.println("client connection: " + clientSocket.getInetAddress());
            new Thread(new FileSenderHandler(clientSocket, filePath)).start();
        } catch(IOException exception){
            System.err.println("error handling file server on port " + port);
        }
    }

    private static class FileSenderHandler implements Runnable {
        private final Socket clientSocket;
        private final String filePath;

        public FileSenderHandler(Socket clientSocket, String filePath) {
            this.clientSocket = clientSocket;
            this.filePath = filePath;
        }

        @Override
        public void run() {
            try(FileInputStream fis = new FileInputStream((filePath))) {
                OutputStream ops = clientSocket.getOutputStream();
                String fileName = new File(filePath).getName();
                String header = "Filename: " + fileName + "\n";
                ops.write(header.getBytes());

                byte[] buffer = new byte[4096];
                int byteRead;
                while ((byteRead = fis.read(buffer)) != -1) {
                    ops.write(buffer, 0, byteRead);
                }
                System.out.println("file " + fileName + " sent to " + clientSocket.getInetAddress());
            } catch (Exception exception) {
                System.err.println("error sending file to client: " + exception.getMessage());
            } finally {
                try {
                    clientSocket.close();
                } catch (Exception exception) {
                    System.err.println("error closing socket: " + exception.getMessage());
                }
            }
        }
    }
}
