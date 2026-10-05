package dev.lodecore;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import com.google.common.net.HostAndPort;

/**
 * @param lodestar          where lodestar listens
 * @param token             lodestar's token
 * @param forwardingSecret  the secret the proxy signs player identities with
 * @param nodeName          this node's name in logs
 * @param advertisedAddress where the proxy reaches this server's game port; empty to work it out
 */
public record LodecoreConfig(HostAndPort lodestar, String token, String forwardingSecret, String nodeName, String advertisedAddress) {
	private static final String TEMPLATE = """
			# Lodecore node configuration.

			# Address of lodestar.
			lodestar=127.0.0.1:25580
			# The token from lodestar.toml.
			token=
			# The forwarding_secret from lodeproxy.toml.
			forwarding-secret=
			# This node's name in logs.
			node-name=node
			# Address the proxy uses to reach this server's game port. Leave empty to
			# use this machine's address and the port from server.properties.
			advertised-address=
			""";

	public static final class InvalidConfigException extends RuntimeException {
		InvalidConfigException(String message) {
			super(message);
		}
	}

	/**
	 * Loads the config, writing a template if there is none. Refuses to go on without the
	 * secrets: a node that cannot verify who sent a player must not accept players at all.
	 */
	public static LodecoreConfig load(Path path) {
		Properties properties = new Properties();

		try {
			if (Files.notExists(path)) {
				Files.createDirectories(path.getParent());
				Files.writeString(path, TEMPLATE);
			}

			try (Reader reader = Files.newBufferedReader(path)) {
				properties.load(reader);
			}
		} catch (IOException e) {
			throw new InvalidConfigException("Could not read " + path + ": " + e);
		}

		String token = properties.getProperty("token", "").trim();
		String forwardingSecret = properties.getProperty("forwarding-secret", "").trim();

		if (token.isEmpty() || forwardingSecret.isEmpty()) {
			throw new InvalidConfigException("Set `token` and `forwarding-secret` in " + path + " before starting this node.");
		}

		HostAndPort lodestar;

		try {
			lodestar = HostAndPort.fromString(properties.getProperty("lodestar", "127.0.0.1:25580").trim()).withDefaultPort(25580);
		} catch (IllegalArgumentException e) {
			throw new InvalidConfigException("`lodestar` in " + path + " is not a valid address: " + e.getMessage());
		}

		return new LodecoreConfig(
				lodestar,
				token,
				forwardingSecret,
				properties.getProperty("node-name", "node").trim(),
				properties.getProperty("advertised-address", "").trim());
	}
}
