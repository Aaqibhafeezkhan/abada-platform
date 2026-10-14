package com.abada.engine.core.model;

import java.io.Serializable;

/**
 * Which human step reviews an agent's output (APL {@code reviewed_by}, E14).
 * Metadata only: it never changes routing. {@code accept} is the review
 * outcome that means the reviewer kept the agent's answer; any other outcome
 * counts as an override.
 */
public record ReviewedByMeta(String agentNode, String reviewNode, String accept) implements Serializable {}
