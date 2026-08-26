package covia.brightside.model;

/** A chat agent the user can pick in the agents pane: its {@code id} (path segment) and display {@code name}. */
public record AgentRef(String id, String name) {
}
