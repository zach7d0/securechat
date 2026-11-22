package com.securechat.contracts.events;

/**
 * Signal ciphertext for one recipient device. {@code ciphertext} is base64 protocol bytes;
 * {@code signalType} 3 = PreKeyWhisperMessage, 1 = WhisperMessage. Never plaintext.
 */
public record Envelope(java.util.UUID deviceId, int signalType, String ciphertext) {
}
