# Banner Customization

`CommandLine.run()` calls `printBanner()` before creating the server context.
The default implementation delegates to
[Banner.printBanner(Logger)](../src/main/java/com/akakata/banner/Banner.java).

## Classpath Banner

Create `src/main/resources/banner.txt` in your application:

```text
====================================
  MY GAME SERVER
  Powered by Zea TCP Transport
====================================
```

The loader looks up `banner.txt` at the classpath root and reads it as UTF-8.
It prints that text to standard output. Missing or unreadable resources fall back
to the ASCII text built into `Banner`; an empty resource produces an empty custom
banner. The repository already includes a
[banner.txt](../src/main/resources/banner.txt) resource.

Classpath resource order determines which `banner.txt` is found when an application
and its dependencies both supply one. Ensure the intended application resource is
selected by your launcher. Banner text is literal: `${version}`, `${env}` and other
placeholders are not expanded.

## Default Runtime Information

After printing banner text, the default implementation prints a
`:: Zea TCP :: (v...)` line to standard output and logs:

- Java version and vendor.
- Operating-system name, version and architecture.
- Process ID from the runtime management bean, or `unknown` on failure.
- Current working directory.

There is no banner-mode property or configuration flag in the current loader.
Override `printBanner()` to replace or suppress this behavior.

## Displayed Version

The Maven project version is **0.8.1**. The current `Banner.getVersion()` looks for
`META-INF/maven/com.akakata/Zea-tcp/pom.properties`, with an uppercase `Z`, and falls
back to `1.0-SNAPSHOT` if that resource is absent or cannot be read.

The library artifact ID in the POM is lowercase `zea-tcp`, so its normal Maven
metadata is under `META-INF/maven/com.akakata/zea-tcp/pom.properties`. Consequently,
the current default banner can display `1.0-SNAPSHOT` even for the 0.8.1 library.
This display value does not describe the actual dependency version. An application
that needs its own accurate version line can print it explicitly in an override.

## Override the Banner

This complete entry point prints an application-owned banner and version:

```java
package com.example;

import com.akakata.CommandLine;

public final class BrandedServer extends CommandLine {
    public static void main(String[] args) {
        new BrandedServer().run(args);
    }

    @Override
    protected void printBanner() {
        System.out.println("MY GAME SERVER — application version 1.0.0");
        System.out.println("Transport dependency: Zea TCP 0.8.1");
    }
}
```

This override replaces the default banner, version lookup and runtime-information
logging. To preserve the default information and append application details, call
`super.printBanner()` before your additional output.

## Disable Banner Output

```java
package com.example;

import com.akakata.CommandLine;

public final class QuietServer extends CommandLine {
    public static void main(String[] args) {
        new QuietServer().run(args);
    }

    @Override
    protected void printBanner() {
        // Suppress banner text and its runtime-information logs.
    }
}
```

Other startup and lifecycle logs still run. Use plain text when your log collector
does not render terminal colors or Unicode art, and keep application version
information separate from environment or region labels.

## Related Documentation

- [Application startup](../README.md#lifecycle-and-components)
- [Configuration loading](CONFIGURATION.md#loading-configuration)
- [CommandLine](../src/main/java/com/akakata/CommandLine.java)
