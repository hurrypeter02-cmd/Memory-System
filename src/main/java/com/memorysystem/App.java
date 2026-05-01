package com.memorysystem;

import com.memorysystem.api.MemoryHttpServer;
import com.memorysystem.service.MemoryService;

public final class App {
    private App() {
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        MemoryHttpServer server = MemoryHttpServer.start(port, new MemoryService());
        System.out.println("Memory Timeline API listening on http://127.0.0.1:" + server.port());
    }
}
