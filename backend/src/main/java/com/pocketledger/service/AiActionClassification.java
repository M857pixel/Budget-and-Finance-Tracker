package com.pocketledger.service;

import java.util.Map;

/** Immutable container for an AI-classified action name and its flat parameter map. */
public record AiActionClassification(String action, Map<String, String> parameters) {}