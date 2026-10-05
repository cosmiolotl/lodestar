package dev.lodecore.forwarding;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Who a connecting player is, as vouched for by the proxy.
 *
 * <p>The format is Velocity's "modern forwarding", version 1: an HMAC-SHA256 signature followed
 * by the signed payload. See {@code crates/lodeproxy/src/forwarding.rs} for the other side.
 */
public record ForwardedIdentity(String address, UUID id, String name, List<Property> properties) {
	private static final int VERSION = 1;
	private static final int SIGNATURE_LENGTH = 32;
	private static final int MAX_PROPERTIES = 16;

	public record Property(String name, String value, String signature) {
	}

	public static final class InvalidIdentityException extends Exception {
		InvalidIdentityException(String message) {
			super(message);
		}
	}

	/** Checks the signature on {@code data} and, only if it is good, reads the identity. */
	public static ForwardedIdentity verify(byte[] data, byte[] secret) throws InvalidIdentityException {
		if (data.length < SIGNATURE_LENGTH) {
			throw new InvalidIdentityException("identity is truncated");
		}

		byte[] expected;

		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret, "HmacSHA256"));
			mac.update(data, SIGNATURE_LENGTH, data.length - SIGNATURE_LENGTH);
			expected = mac.doFinal();
		} catch (NoSuchAlgorithmException | InvalidKeyException e) {
			throw new IllegalStateException(e);
		}

		// MessageDigest.isEqual compares in constant time.
		if (!MessageDigest.isEqual(expected, Arrays.copyOf(data, SIGNATURE_LENGTH))) {
			throw new InvalidIdentityException("identity is not signed with this cluster's forwarding secret");
		}

		ByteBuffer payload = ByteBuffer.wrap(data, SIGNATURE_LENGTH, data.length - SIGNATURE_LENGTH);

		try {
			int version = varInt(payload);

			if (version != VERSION) {
				throw new InvalidIdentityException("unsupported forwarding version " + version);
			}

			String address = string(payload);
			UUID id = new UUID(payload.getLong(), payload.getLong());
			String name = string(payload);
			int count = varInt(payload);

			if (count < 0 || count > MAX_PROPERTIES) {
				throw new InvalidIdentityException("unreasonable property count " + count);
			}

			List<Property> properties = new ArrayList<>(count);

			for (int i = 0; i < count; i++) {
				String propertyName = string(payload);
				String value = string(payload);
				String signature = payload.get() != 0 ? string(payload) : null;
				properties.add(new Property(propertyName, value, signature));
			}

			return new ForwardedIdentity(address, id, name, List.copyOf(properties));
		} catch (BufferUnderflowException e) {
			throw new InvalidIdentityException("identity is truncated");
		}
	}

	private static int varInt(ByteBuffer buffer) throws InvalidIdentityException {
		int value = 0;

		for (int shift = 0; shift < 35; shift += 7) {
			byte b = buffer.get();
			value |= (b & 0x7f) << shift;

			if ((b & 0x80) == 0) {
				return value;
			}
		}

		throw new InvalidIdentityException("varint is too long");
	}

	private static String string(ByteBuffer buffer) throws InvalidIdentityException {
		int length = varInt(buffer);

		if (length < 0 || length > buffer.remaining()) {
			throw new BufferUnderflowException();
		}

		String value = new String(buffer.array(), buffer.arrayOffset() + buffer.position(), length, StandardCharsets.UTF_8);
		buffer.position(buffer.position() + length);
		return value;
	}
}
