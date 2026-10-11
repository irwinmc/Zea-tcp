package com.akakata.banner;

import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * Loads an optional classpath banner and logs server version and process information.
 *
 * @author Kelvin
 */
public class Banner {

    /**
     * Creates a banner instance.
     */
    public Banner() {
    }

    /**
     * Fallback startup banner used when no custom banner is available.
     */
    private static final String DEFAULT_BANNER =
            "\n" +
            "  __________ _____ \n" +
            " |___  / __|  _  |\n" +
            "    / /| |__| | | |\n" +
            "   / / |  __| |_| |\n" +
            "  /_/  |_|  |_| |_| TCP\n";

    /**
     * Protocol schema version or application version identifier.
     */
    private static final String VERSION = getVersion();

    /**
     * Java runtime version captured from system properties.
     */
    private static final String JAVA_VERSION = System.getProperty("java.version");

    /**
     * Operating-system name captured from system properties.
     */
    private static final String OS_NAME = System.getProperty("os.name");

    /**
     * Operating-system architecture captured from system properties.
     */
    private static final String OS_ARCH = System.getProperty("os.arch");

    /**
     * Loads and logs the configured banner with version and process information.
     *
     * @param logger the logger to use
     */
    public static void printBanner(Logger logger) {
        String banner = loadCustomBanner();
        if (banner == null) {
            banner = DEFAULT_BANNER;
        }

        // Print banner
        System.out.println(banner);

        // Print version info
        String versionInfo = String.format(
                " :: Zea TCP ::                (v%s)",
                VERSION
        );
        System.out.println(versionInfo);

        // Print system info
        logger.info("Java version: {}, Vendor: {}", JAVA_VERSION, System.getProperty("java.vendor"));
        logger.info("OS: {} ({}), Arch: {}", OS_NAME, System.getProperty("os.version"), OS_ARCH);
        logger.info("PID: {}", getProcessId());
        logger.info("Working directory: {}", System.getProperty("user.dir"));

        System.out.println();
    }

    /**
     * Loads optional banner text from its classpath resource.
     *
     * @return the custom banner, or {@code null} when unavailable
     */
    private static String loadCustomBanner() {
        try (InputStream is = Banner.class.getClassLoader().getResourceAsStream("banner.txt")) {
            if (is != null) {
                StringBuilder sb = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line).append("\n");
                    }
                }
                return sb.toString();
            }
        } catch (Exception e) {
            // Ignore and use default
        }
        return null;
    }

    /**
     * Get version from pom.properties or default.
     *
     * @return version string
     */
    private static String getVersion() {
        try (InputStream is = Banner.class.getClassLoader()
                .getResourceAsStream("META-INF/maven/com.akakata/Zea-tcp/pom.properties")) {
            if (is != null) {
                Properties props = new Properties();
                props.load(is);
                return props.getProperty("version", "1.0-SNAPSHOT");
            }
        } catch (Exception e) {
            // Ignore
        }
        return "1.0-SNAPSHOT";
    }

    /**
     * Get current process ID.
     *
     * @return process ID as string
     */
    private static String getProcessId() {
        try {
            String processName = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
            return processName.split("@")[0];
        } catch (Exception e) {
            return "unknown";
        }
    }
}
