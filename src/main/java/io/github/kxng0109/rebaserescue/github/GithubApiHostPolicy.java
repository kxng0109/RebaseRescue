package io.github.kxng0109.rebaserescue.github;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Allowlist validation for the GitHub API base URL (SSRF defense).
 *
 * <p>The base URL host must exactly match one entry of the explicit
 * allowlist (default {@code api.github.com}; add a GitHub Enterprise Server
 * FQDN to opt in). Scheme must be {@code https} with no userinfo, no
 * non-443 port, and no path, query, or fragment. Loopback, localhost, and
 * cloud metadata endpoints are never accepted, even when listed. Private
 * network literals are accepted only through explicit listing, for internal
 * Enterprise Server instances. Blank input preserves the historical fallback
 * to the default, and trailing slashes are normalized.</p>
 */
public final class GithubApiHostPolicy {

    /** Default base URL used when none is configured. */
    public static final String DEFAULT_BASE_URL = "https://api.github.com";

    /** Default allowlist containing only the public GitHub API host. */
    public static final List<String> DEFAULT_ALLOWED_HOSTS = List.of("api.github.com");

    private static final Pattern IPV4_LITERAL =
            Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");

    private GithubApiHostPolicy() {
    }

    /**
     * Validates the configured base URL against the default allowlist and
     * returns its canonical form.
     *
     * @param baseUrl the configured value, may be {@code null} or blank
     * @return the canonical base URL, never {@code null} or blank
     * @throws IllegalArgumentException when the value is not allowlisted
     */
    public static String normalize(String baseUrl) {
        return normalize(baseUrl, DEFAULT_ALLOWED_HOSTS);
    }

    /**
     * Validates the configured base URL against the given allowlist and
     * returns its canonical form.
     *
     * @param baseUrl the configured value, may be {@code null} or blank
     * @param allowedHosts the explicit allowlist, must not be {@code null} or empty
     * @return the canonical base URL, never {@code null} or blank
     * @throws IllegalArgumentException when the value or the list is not acceptable
     */
    public static String normalize(String baseUrl, List<String> allowedHosts) {
        List<String> allowed = canonicalizeAllowedHosts(allowedHosts);
        if (baseUrl == null || baseUrl.isBlank()) {
            return DEFAULT_BASE_URL;
        }
        String trimmed = baseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        validate(trimmed, allowed);
        if (isDefaultHost(trimmed, allowed)) {
            return DEFAULT_BASE_URL;
        }
        return trimmed;
    }

    /**
     * Validates that the given base URL targets the default allowlist.
     *
     * @param baseUrl the value to check, must not be {@code null}
     * @throws IllegalArgumentException when the value is not allowlisted
     */
    public static void validate(String baseUrl) {
        validate(baseUrl, DEFAULT_ALLOWED_HOSTS);
    }

    /**
     * Validates that the given base URL targets the given allowlist.
     *
     * @param baseUrl the value to check, must not be {@code null} or blank
     * @param allowedHosts the explicit allowlist, must not be {@code null} or empty
     * @throws IllegalArgumentException when the value is not allowlisted
     */
    public static void validate(String baseUrl, List<String> allowedHosts) {
        List<String> allowed = canonicalizeAllowedHosts(allowedHosts);
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("GitHub API base URL must not be blank");
        }
        URI uri;
        try {
            uri = URI.create(baseUrl.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("GitHub API base URL is not a valid URI");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("GitHub API base URL must use https");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("GitHub API base URL must not contain credentials");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("GitHub API base URL must contain a host");
        }
        String lowerHost = host.toLowerCase(Locale.ROOT);
        if (isNeverListable(lowerHost)) {
            throw new IllegalArgumentException(
                    "GitHub API base URL targets a blocked address");
        }
        if (!allowed.contains(lowerHost)) {
            throw new IllegalArgumentException(
                    "GitHub API base URL host is not allowlisted");
        }
        int port = uri.getPort();
        if (port != -1 && port != 443) {
            throw new IllegalArgumentException("GitHub API base URL must not specify a non-https port");
        }
        String path = uri.getPath();
        if (path != null && !path.isEmpty() && !"/".equals(path)) {
            throw new IllegalArgumentException("GitHub API base URL must not contain a path");
        }
        if (uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("GitHub API base URL must not contain a query or fragment");
        }
    }

    private static List<String> canonicalizeAllowedHosts(List<String> allowedHosts) {
        if (allowedHosts == null || allowedHosts.isEmpty()) {
            throw new IllegalArgumentException("Allowed API hosts must not be empty");
        }
        List<String> canonical = new ArrayList<>();
        for (String entry : allowedHosts) {
            if (entry == null || entry.isBlank()) {
                throw new IllegalArgumentException("Allowed API hosts must not contain blank entries");
            }
            String host = entry.trim().toLowerCase(Locale.ROOT);
            if (isNeverListable(host)) {
                throw new IllegalArgumentException("Allowed API hosts must not contain blocked addresses");
            }
            canonical.add(host);
        }
        return canonical;
    }

    private static boolean isDefaultHost(String baseUrl, List<String> allowed) {
        if (!allowed.contains("api.github.com")) {
            return false;
        }
        return "api.github.com".equalsIgnoreCase(URI.create(baseUrl).getHost());
    }

    /**
     * Addresses that are never legitimate GitHub API targets, even through
     * explicit listing: loopback, localhost names, IPv6 literals, and cloud
     * metadata endpoints. Private IPv4 literals are handled separately so
     * internal Enterprise Server instances can opt in explicitly.
     */
    private static boolean isNeverListable(String host) {
        return isLoopbackIpv4(host)
                || host.contains(":")
                || "localhost".equals(host)
                || host.endsWith(".localhost")
                || "metadata.google.internal".equals(host)
                || host.endsWith(".internal")
                || "169.254.169.254".equals(host)
                || "0.0.0.0".equals(host);
    }

    /**
     * Reports whether the host is a private-network IPv4 literal. Used to
     * warn operators about explicitly listed internal addresses, which are
     * accepted only through explicit listing.
     *
     * @param host the host to check, may be {@code null}
     * @return {@code true} for RFC 1918, link-local, and similar ranges
     */
    public static boolean isPrivateIpv4(String host) {
        return host != null && isNonPublicIpv4(host.toLowerCase(Locale.ROOT));
    }

    private static boolean isNonPublicIpv4(String host) {
        if (!IPV4_LITERAL.matcher(host).matches()) {
            return false;
        }
        // The literal pattern guarantees four ASCII numeric octets, so parsing cannot fail.
        String[] parts = host.split("\\.", -1);
        int first = Integer.parseInt(parts[0]);
        int second = Integer.parseInt(parts[1]);
        for (String part : parts) {
            int octet = Integer.parseInt(part);
            if (octet < 0 || octet > 255) {
                return true;
            }
        }
        if (first == 10 || first == 127 || first == 0) {
            return true;
        }
        if (first == 172 && second >= 16 && second <= 31) {
            return true;
        }
        if (first == 192 && second == 168) {
            return true;
        }
        return first == 169 && second == 254;
    }

    private static boolean isLoopbackIpv4(String host) {
        if (!IPV4_LITERAL.matcher(host).matches()) {
            return false;
        }
        // Same guarantee as above: ASCII numeric octets always parse.
        String[] parts = host.split("\\.", -1);
        return Integer.parseInt(parts[0]) == 127 || "0.0.0.0".equals(host);
    }
}
