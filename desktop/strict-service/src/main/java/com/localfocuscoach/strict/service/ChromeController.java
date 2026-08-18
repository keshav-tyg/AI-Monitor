package com.localfocuscoach.strict.service;

public interface ChromeController {
    enum QuitResult {
        REQUESTED,
        FAILED
    }

    boolean isRunning();

    QuitResult requestGracefulQuit();
}
