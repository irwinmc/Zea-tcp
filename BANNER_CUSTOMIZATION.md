# Banner Customization Guide

Zea-tcp displays a startup banner similar to Spring Boot. You can customize it in several ways.

## Default Banner

The default banner looks like this:

```
  ████████╗ ██████╗██████╗
  ╚══██╔══╝██╔════╝██╔══██╗
     ██║   ██║     ██████╔╝
     ██║   ██║     ██╔═══╝
     ██║   ╚██████╗██║
     ╚═╝    ╚═════╝╚═╝

 :: Zea TCP ::                (v1.0-SNAPSHOT)
```

Plus system information like Java version, OS, PID, etc.

---

## Method 1: Custom banner.txt File

Create a file named `banner.txt` in your `src/main/resources` directory:

```
src/main/resources/
  └── banner.txt
```

**Example banner.txt:**
```
╔═══════════════════════════════════╗
║   MY AWESOME GAME SERVER          ║
║   Powered by Zea TCP              ║
╚═══════════════════════════════════╝
```

The custom banner will be loaded automatically at startup.

---

## Method 2: Override printBanner() Method

You can programmatically customize the banner in your server class:

```java
public class MyGameServer extends CommandLine {

    @Override
    protected void printBanner() {
        System.out.println();
        System.out.println("  ╔══════════════════════════════════╗");
        System.out.println("  ║   MY CUSTOM GAME SERVER          ║");
        System.out.println("  ║   Version 2.0                    ║");
        System.out.println("  ╚══════════════════════════════════╝");
        System.out.println();

        // Optionally call super to show system info
        // super.printBanner();
    }
}
```

---

## Method 3: Disable Banner

To disable the banner completely:

```java
public class MyGameServer extends CommandLine {

    @Override
    protected void printBanner() {
        // Do nothing - no banner
    }
}
```

---

## ASCII Art Generators

Use these tools to create ASCII art banners:

1. **Text to ASCII Art Generator**: http://patorjk.com/software/taag/
   - Font recommendation: "ANSI Shadow", "Big", "Standard"

2. **FIGlet**: Command-line tool
   ```bash
   figlet -f standard "ZEA TCP"
   ```

3. **ASCII Art Generator**: https://www.ascii-art-generator.org/

---

## Banner Examples

### Example 1: Minimalist
```
━━━━━━━━━━━━━━━━━━━━━━━━━━
  GAME SERVER v2.0
━━━━━━━━━━━━━━━━━━━━━━━━━━
```

### Example 2: Box Style
```
┌────────────────────────────┐
│   MY GAME SERVER           │
│   Powered by Zea TCP       │
└────────────────────────────┘
```

### Example 3: Large ASCII
```
  ██████╗  █████╗ ███╗   ███╗███████╗
 ██╔════╝ ██╔══██╗████╗ ████║██╔════╝
 ██║  ███╗███████║██╔████╔██║█████╗
 ██║   ██║██╔══██║██║╚██╔╝██║██╔══╝
 ╚██████╔╝██║  ██║██║ ╚═╝ ██║███████╗
  ╚═════╝ ╚═╝  ╚═╝╚═╝     ╚═╝╚══════╝
```

### Example 4: Simple Text with Colors
```java
@Override
protected void printBanner() {
    // ANSI colors (works in most terminals)
    String CYAN = "\u001B[36m";
    String RESET = "\u001B[0m";

    System.out.println();
    System.out.println(CYAN + "  ╔══════════════════════════╗" + RESET);
    System.out.println(CYAN + "  ║   EPIC GAME SERVER       ║" + RESET);
    System.out.println(CYAN + "  ╚══════════════════════════╝" + RESET);
    System.out.println();
}
```

---

## System Information Display

The default banner includes:
- Java version and vendor
- Operating system and architecture
- Process ID (PID)
- Working directory

You can customize what information to show:

```java
@Override
protected void printBanner() {
    System.out.println("  MY GAME SERVER");
    System.out.println();

    // Show only specific info
    LOG.info("Version: 2.0.0");
    LOG.info("Environment: {}",
        System.getProperty("env", "production"));
    LOG.info("Max Memory: {} MB",
        Runtime.getRuntime().maxMemory() / 1024 / 1024);
}
```

---

## Tips

1. **Keep it simple**: Overly complex banners can clutter logs
2. **Test in production**: Some log aggregators may not display ASCII art well
3. **Consider width**: Keep banners under 80 characters wide for compatibility
4. **Use ANSI colors carefully**: Not all environments support them
5. **Version info**: Include version numbers in your custom banner

---

## Resources

- Default banner: `src/main/resources/banner.txt`
- Banner class: `com.akakata.banner.Banner`
- Override method: `CommandLine.printBanner()`

---

## Complete Example

**src/main/resources/banner.txt:**
```
  ███╗   ███╗██╗   ██╗     ██████╗  █████╗ ███╗   ███╗███████╗
  ████╗ ████║╚██╗ ██╔╝    ██╔════╝ ██╔══██╗████╗ ████║██╔════╝
  ██╔████╔██║ ╚████╔╝     ██║  ███╗███████║██╔████╔██║█████╗
  ██║╚██╔╝██║  ╚██╔╝      ██║   ██║██╔══██║██║╚██╔╝██║██╔══╝
  ██║ ╚═╝ ██║   ██║       ╚██████╔╝██║  ██║██║ ╚═╝ ██║███████╗
  ╚═╝     ╚═╝   ╚═╝        ╚═════╝ ╚═╝  ╚═╝╚═╝     ╚═╝╚══════╝
```

**MyGameServer.java:**
```java
public class MyGameServer extends CommandLine {

    public static void main(String[] args) {
        new MyGameServer().run(args);
    }

    @Override
    protected void printBanner() {
        // Use custom banner.txt from resources
        super.printBanner();

        // Add extra info
        System.out.println("  Environment: Production");
        System.out.println("  Region: US-WEST");
        System.out.println();
    }
}
```

**Output:**
```
  ███╗   ███╗██╗   ██╗     ██████╗  █████╗ ███╗   ███╗███████╗
  ████╗ ████║╚██╗ ██╔╝    ██╔════╝ ██╔══██╗████╗ ████║██╔════╝
  ██╔████╔██║ ╚████╔╝     ██║  ███╗███████║██╔████╔██║█████╗
  ██║╚██╔╝██║  ╚██╔╝      ██║   ██║██╔══██║██║╚██╔╝██║██╔══╝
  ██║ ╚═╝ ██║   ██║       ╚██████╔╝██║  ██║██║ ╚═╝ ██║███████╗
  ╚═╝     ╚═╝   ╚═╝        ╚═════╝ ╚═╝  ╚═╝╚═╝     ╚═╝╚══════╝

 :: Zea TCP ::                (v1.0-SNAPSHOT)

2025-01-19 10:30:45.123  INFO --- [main] Banner: Java version: 21.0.1, Vendor: Oracle Corporation
2025-01-19 10:30:45.124  INFO --- [main] Banner: OS: Mac OS X (14.0), Arch: aarch64
2025-01-19 10:30:45.125  INFO --- [main] Banner: PID: 12345
2025-01-19 10:30:45.126  INFO --- [main] Banner: Working directory: /Users/you/game-server

  Environment: Production
  Region: US-WEST
```
