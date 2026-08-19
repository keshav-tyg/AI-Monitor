package com.localfocuscoach.strict.service;

/** Opens the service-owned desktop dashboard without accepting browser-controlled input. */
@FunctionalInterface
public interface DashboardLauncher {
    void open();
}
