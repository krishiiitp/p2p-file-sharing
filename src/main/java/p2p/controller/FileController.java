package p2p.controller;

import com.sun.net.httpserver.*;
import jdk.jpackage.internal.IOUtils;
import p2p.service.FileSharer;

import java.io.*;
import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FileController {
    private final FileSharer fileSharer;
    private final HttpServer server;
    private final String uploadDir;
    private final ExecutorService executorService;

    public FileController(int port) throws IOException {
        this.fileSharer = new FileSharer();
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.uploadDir = System.getProperty("java.io.tmpdir") + File.separator + "safeshare-uploads";
        this.executorService = Executors.newFixedThreadPool(10);

        File uploadDirFile = new File(uploadDir);
        if (!uploadDirFile.exists()) {
            uploadDirFile.mkdirs();
        }

        server.createContext("/upload", new UploadHandler());
        server.createContext("/download", new DownloadHandler());
        server.createContext("/", new CORSHandler());
        server.setExecutor(executorService);
    }

    public void start() {
        server.start();
        System.out.println("api server started on port: " + server.getAddress().getPort());
    }

    public void stop() {
        server.stop(0);
        executorService.shutdown();
        System.out.println("api server stopped");
    }

    private class CORSHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Headers headers = exchange.getResponseHeaders();
            headers.add("Access-Control-Allow-Origin", "*");
            headers.add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            headers.add("Access-Control-Allow-Headers", "Content-Type,Authorization");

            if (exchange.getRequestMethod().equals("OPTIONS")) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            String response = "NOT FOUND";
            exchange.sendResponseHeaders(404, response.getBytes().length);
            try (OutputStream ops = exchange.getResponseBody()) {
                ops.write(response.getBytes());
            }
        }
    }

    private class UploadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Headers headers = exchange.getResponseHeaders();
            headers.add("Access-Control-Allow-Origin", "*");

            if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                String response = "Method not allowed";
                exchange.sendResponseHeaders(405, response.getBytes().length);
                try (OutputStream ops = exchange.getResponseBody()) {
                    ops.write(response.getBytes());
                }
                return;
            }

            Headers requestHeaders = exchange.getRequestHeaders();
            String contentType = requestHeaders.getFirst("Content-Type");
            if (contentType == null || !contentType.startsWith("multipart/form-data")) {
                String response = "Bad Request: Content-Type must be multipart/form-data";
                exchange.sendResponseHeaders(400, response.getBytes().length);
                try (OutputStream ops = exchange.getResponseBody()) {
                    ops.write(response.getBytes());
                }
                return;
            }

            try {
                // do parsing
                String boundary = contentType.substring(contentType.indexOf("boundary=") + 9);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();

                IOUtils.copy(exchange.getRequestBody(), baos);
                byte[] requestData = baos.toByteArray();

                Multiparser parser = new Multiparser(requestData, boundary);
                Multiparser.ParseResult result = parser.parse();

                if (result == null) {
                    String response = "Bad Request: Could not Parse File Content";
                    exchange.sendResponseHeaders(400, response.getBytes().length);
                    try (OutputStream ops = exchange.getResponseBody()) {
                        ops.write(response.getBytes());
                    }
                    return;
                }

                String fileName = result.fileName;
                if (fileName == null || fileName.trim().isEmpty()) {
                    fileName = "unnamed-file";
                }

                String uniqueFileName = UUID.randomUUID().toString() + "_" + new File(fileName).getName();
                String filePath = uploadDir + File.separator + uniqueFileName;

                try (FileOutputStream fos = new FileOutputStream(filePath)){
                    fos.write(result.fileContent);
                }
                int port = fileSharer.offerFile(filePath);
                new Thread(() -> fileSharer.startFileServer(port));
                String jsonResponse = "{\"port\": " + port + "}";
                headers.add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, jsonResponse.getBytes().length);
                try (OutputStream ops = exchange.getResponseBody()) {
                    ops.write(jsonResponse.getBytes());
                }

            } catch (Exception exception) {
                System.err.println("error processing file upload: " + exception.getMessage());
                String response = "server error: " + exception.getMessage();
                exchange.sendResponseHeaders(500, response.getBytes().length);
                try (OutputStream ops = exchange.getResponseBody()) {
                    ops.write(response.getBytes());
                }
            }
        }
    }

    private static class Multiparser {
        private final byte[] data;
        private final String boundary;

        public Multiparser(byte[] data, String boundary) {
            this.data = data;
            this.boundary = boundary;
        }

        public ParseResult parse() {
            try {
                String dataAsString = new String(data); // doesnt work for videos TODO
                String filenameMarker = "filename=\"";
                int filenameStart = dataAsString.indexOf(filenameMarker);
                if (filenameStart == -1) {
                    return null;
                }

                int filenameEnd = dataAsString.indexOf("\"", filenameStart);
                String fileName = dataAsString.substring(filenameStart, filenameEnd);

                String contentTypeMarker = "Content-Type: ";
                String contentType = "application/octet-stream";
                int contentTypeStart = dataAsString.indexOf(contentTypeMarker, filenameEnd);
                if (contentTypeStart != -1) {
                    contentTypeStart += contentTypeMarker.length();
                    int contentTypeEnd = dataAsString.indexOf("\r\n", contentTypeStart);
                    contentType = dataAsString.substring(contentTypeStart, contentTypeEnd);
                }

                String headerEndMarker = "\r\n\r\n";
                int headerEnd = dataAsString.indexOf(headerEndMarker);
                if (headerEnd == -1) {
                    return null;
                }
                int contentStart = headerEnd + headerEndMarker.length();
                byte[] boundaryBytes = ("\r\n--" + boundary + "--").getBytes();
                int contentEnd = findSequence(data, boundaryBytes, contentStart);
                if (contentEnd == -1) {
                    boundaryBytes = ("\r\n--" + boundary).getBytes();
                    contentEnd = findSequence(data, boundaryBytes, contentStart);
                }
                if (contentEnd == -1 || contentEnd <= contentStart) {
                    return null;
                }

                byte[] fileContent = new byte[contentEnd - contentStart];
                System.arraycopy(data, contentStart, fileContent, 0, fileContent.length);

                return new ParseResult(fileName, fileContent, contentType);
            } catch (Exception exception) {
                System.out.println("error parsing multipart data: " + exception.getMessage());
                return null;
            }
        }

        public static class ParseResult {
            public final String fileName;
            public final byte[] fileContent;
            public final String contentType;

            public ParseResult(String fileName, byte[] fileContent, String contentType) {
                this.fileName = fileName;
                this.fileContent = this.fileContent;
                this.contentType = contentType;
            }
        }
    }

    private int findSequence(byte[] data, byte[] sequence, int startPos) {
        outer:
            for (int i = startPos; i <= (data.length - sequence.length); i ++) {
                for (int j = 0; j < sequence.length; j ++) {
                    if (data[i + j] != sequence[j]) {
                        continue outer;
                    }
                }
                return i;
            }
            return -1;
    }

    private class DownloadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Headers headers = exchange.getResponseHeaders();
            headers.add("Access-Control-Allow-Origin", "*");

            if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
                String response = "Method not Allowed";
                exchange.sendResponseHeaders(405, response.getBytes().length);
                try (OutputStream ops = exchange.getResponseBody()) {
                    ops.write(response.getBytes());
                }
                return;
            }

            String path = exchange.getRequestURI().getPath();
            String portStr = path.substring(path.lastIndexOf('/') + 1);
            try {
                int port = Integer.parseInt(portStr);
                try (Socket socket = new Socket("localhost", port)) {
                    InputStream socketInput = socket.getInputStream();
                    File tmpFile = File.createTempFile("download-", ".tmp");
                    String fileName = "downloaded-file";
                    try (FileOutputStream fos = new FileOutputStream(tmpFile)) {
                        byte[] buffer = new byte[4096];
                        int byteRead;
                        ByteArrayOutputStream headerBaos = new ByteArrayOutputStream();
                        int b;
                        while ((b = socketInput.read()) != -1) {
                            if (b == '\n') {
                                break;
                            }
                            headerBaos.write(b);
                        }
                        String header = headerBaos.toString().trim();
                        if (header.startsWith("Filename: ")) {
                            fileName = header.substring("Filename: ".length());
                        }
                        while ((byteRead = socketInput.read(buffer)) != -1) {
                            fos.write(buffer, 0, byteRead);
                        }
                    }
                    headers.add("Content-Disposition", "attachment; filename=\"" + fileName + "\"");
                    headers.add("Content-Type", "application/octet-stream");
                    exchange.sendResponseHeaders(200, tmpFile.length());
                    try (OutputStream ops = exchange.getResponseBody()) {
                        FileInputStream fis = new FileInputStream(tmpFile);
                        byte[] buffer = new byte[4096];
                        int bytesRead;
                        while ((bytesRead = fis.read(buffer)) != -1) {
                            ops.write(buffer, 0, bytesRead);
                        }
                    }
                    tmpFile.delete();
                }
            } catch (Exception exception) {
                String response = "error downloading the file: " + exception.getMessage();
                System.out.println(response);
                headers.add("Content-Type", "text/plain");
                exchange.sendResponseHeaders(400, response.getBytes().length);
                try (OutputStream ops = exchange.getResponseBody()) {
                    ops.write(response.getBytes());
                }
            }
        }
    }
}
