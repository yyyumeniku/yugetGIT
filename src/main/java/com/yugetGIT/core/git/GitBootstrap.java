package com.yugetGIT.core.git;

import com.yugetGIT.config.StateProperties;
import com.yugetGIT.util.OsDetector;
import com.yugetGIT.util.PlatformPaths;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class GitBootstrap {

    private static final String LFS_VERSION = "v3.5.1";

    public static boolean isGitResolved() {
        return StateProperties.getGitPath() != null && !StateProperties.getGitPath().isEmpty() 
            && StateProperties.isGitLfsAvailable();
    }

    public static void downloadPortableGit(java.util.function.Consumer<Integer> onProgress, Runnable onComplete, Runnable onCancel) {
        new Thread(() -> {
            try {
                Path yugetDir = PlatformPaths.getYugetGITDir();
                Path binDir = yugetDir.resolve("bin");
                Files.createDirectories(binDir);
                System.out.println("[yugetGIT] Local component directory: " + binDir.toAbsolutePath());

                OsDetector.OS os = OsDetector.detectOS();
                OsDetector.Arch arch = OsDetector.detectArch();

                boolean hasGit = commandSuccess("git", "--version");
                boolean hasLfs = commandSuccess("git", "lfs", "version") || localLfsExists(binDir, os);

                if (!hasGit) {
                    if (os == OsDetector.OS.WINDOWS) {
                        // Stage 1 target for this fix is robust LFS local install; Windows Git bootstrap can be added next.
                        throw new RuntimeException("Git is missing. Automatic Git download is not implemented yet in this build.");
                    }
                    throw new RuntimeException("Git is missing. Install Git first, then yugetGIT will install Git-LFS locally.");
                }

                if (!hasLfs) {
                    String lfsUrl = getGitLfsUrl(os, arch);
                    if (lfsUrl == null) {
                        throw new RuntimeException("No Git-LFS package available for OS/arch: " + os + "/" + arch);
                    }

                    Path tempZip = binDir.resolve("git-lfs-temp.zip");
                    System.out.println("[yugetGIT] Downloading Git-LFS from: " + lfsUrl);
                    downloadFile(lfsUrl, tempZip, onProgress);
                    extractGitLfsFromZip(tempZip, binDir, os);
                    Files.deleteIfExists(tempZip);
                    System.out.println("[yugetGIT] Git-LFS installed locally at: " + getLocalLfsBinary(binDir, os).toAbsolutePath());
                }

                onProgress.accept(100);
                extractAndValidate();
                onComplete.run();
            } catch (Exception e) {
                System.err.println("[yugetGIT] Download failed: " + e.getMessage());
                e.printStackTrace();
                onCancel.run();
            }
        }).start();
    }

    public static void resolveFromPath() {
        try {
            String versionOutput = commandOutput("git", "--version");
            if (versionOutput != null && !versionOutput.isEmpty()) {
                StateProperties.setGitPath("git"); // Just rely on PATH
                StateProperties.setGitVersion(parseVersion(versionOutput));
                
                Path binDir = PlatformPaths.getYugetGITDir().resolve("bin");
                OsDetector.OS os = OsDetector.detectOS();
                boolean lfsAvailable = commandSuccess("git", "lfs", "version");

                if (!lfsAvailable && localLfsExists(binDir, os)) {
                    String localLfs = getLocalLfsBinary(binDir, os).toAbsolutePath().toString();
                    lfsAvailable = commandSuccess(localLfs, "version");
                }

                StateProperties.setGitLfsAvailable(lfsAvailable);
            } else {
                StateProperties.setGitPath(null);
                StateProperties.setGitLfsAvailable(false);
            }
        } catch (Exception e) {
            System.err.println("[yugetGIT] Git not found on PATH or validation failed.");
            StateProperties.setGitPath(null);
            StateProperties.setGitLfsAvailable(false);
        }
    }

    public static void extractAndValidate() {
        Path binDir = PlatformPaths.getYugetGITDir().resolve("bin");
        OsDetector.OS os = OsDetector.detectOS();
        Path localLfs = getLocalLfsBinary(binDir, os);

        if (Files.exists(localLfs)) {
            try {
                commandSuccess(localLfs.toAbsolutePath().toString(), "install", "--skip-repo");
            } catch (Exception ignored) {
                // If install fails, normal git lfs fallback detection still runs.
            }
        }
        resolveFromPath();
    }

    private static String getGitLfsUrl(OsDetector.OS os, OsDetector.Arch arch) {
        String base = "https://github.com/git-lfs/git-lfs/releases/download/" + LFS_VERSION + "/";
        switch (os) {
            case MACOS:
                return base + "git-lfs-darwin-" + (arch == OsDetector.Arch.ARM64 ? "arm64" : "amd64") + "-" + LFS_VERSION + ".zip";
            case WINDOWS:
                return base + "git-lfs-windows-" + (arch == OsDetector.Arch.ARM64 ? "arm64" : "amd64") + "-" + LFS_VERSION + ".zip";
            default:
                return null;
        }
    }

    private static void downloadFile(String urlStr, Path target, java.util.function.Consumer<Integer> onProgress) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setInstanceFollowRedirects(false);

        int code = conn.getResponseCode();
        while (code == HttpURLConnection.HTTP_MOVED_PERM
            || code == HttpURLConnection.HTTP_MOVED_TEMP
            || code == HttpURLConnection.HTTP_SEE_OTHER
            || code == 307
            || code == 308) {
            String next = conn.getHeaderField("Location");
            if (next == null || next.isEmpty()) {
                throw new RuntimeException("Redirect without location while downloading Git-LFS");
            }
            conn = (HttpURLConnection) new URL(next).openConnection();
            conn.setInstanceFollowRedirects(false);
            code = conn.getResponseCode();
        }

        if (code < 200 || code >= 300) {
            throw new RuntimeException("Failed to download Git-LFS. HTTP status: " + code);
        }

        int totalBytes = conn.getContentLength();
        try (InputStream raw = conn.getInputStream();
             BufferedInputStream in = new BufferedInputStream(raw);
             FileOutputStream out = new FileOutputStream(target.toFile())) {

            byte[] buffer = new byte[8192];
            int read;
            long downloaded = 0;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                downloaded += read;
                if (totalBytes > 0) {
                    int percent = (int) Math.min(95, (downloaded * 95L) / totalBytes);
                    onProgress.accept(percent);
                }
            }
        }
    }

    private static void extractGitLfsFromZip(Path zipPath, Path binDir, OsDetector.OS os) throws Exception {
        String desiredName = os == OsDetector.OS.WINDOWS ? "git-lfs.exe" : "git-lfs";
        boolean extracted = false;

        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipPath))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }

                String name = entry.getName();
                if (name.endsWith("/" + desiredName) || name.equals(desiredName)) {
                    Path target = binDir.resolve(desiredName);
                    Files.copy(zis, target, StandardCopyOption.REPLACE_EXISTING);
                    extracted = true;
                }
            }
        }

        if (!extracted) {
            throw new RuntimeException("Could not find " + desiredName + " inside downloaded archive.");
        }

        if (os != OsDetector.OS.WINDOWS) {
            Path lfs = binDir.resolve(desiredName);
            Set<PosixFilePermission> permissions = new HashSet<>(Files.getPosixFilePermissions(lfs));
            permissions.add(PosixFilePermission.OWNER_EXECUTE);
            permissions.add(PosixFilePermission.OWNER_READ);
            permissions.add(PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(lfs, permissions);
        }
    }

    private static Path getLocalLfsBinary(Path binDir, OsDetector.OS os) {
        return binDir.resolve(os == OsDetector.OS.WINDOWS ? "git-lfs.exe" : "git-lfs");
    }

    private static boolean localLfsExists(Path binDir, OsDetector.OS os) {
        return Files.exists(getLocalLfsBinary(binDir, os));
    }

    private static boolean commandSuccess(String... command) {
        return commandOutput(command) != null;
    }

    private static String commandOutput(String... command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            Map<String, String> env = pb.environment();
            env.put("GIT_TERMINAL_PROMPT", "0");

            Path binDir = PlatformPaths.getYugetGITDir().resolve("bin");
            if (Files.exists(binDir)) {
                String path = env.getOrDefault("PATH", "");
                String sep = OsDetector.detectOS() == OsDetector.OS.WINDOWS ? ";" : ":";
                env.put("PATH", binDir.toAbsolutePath().toString() + sep + path);
            }

            Process process = pb.start();
            boolean done = process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            if (!done || process.exitValue() != 0) {
                return null;
            }

            try (InputStream in = process.getInputStream();
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[1024];
                int len;
                while ((len = in.read(buf)) != -1) {
                    out.write(buf, 0, len);
                }
                return new String(out.toByteArray()).trim();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static String parseVersion(String output) {
        Pattern p = Pattern.compile("git version (\\d+\\.\\d+\\.\\d+)");
        Matcher m = p.matcher(output);
        if (m.find()) {
            return m.group(1);
        }
        return "unknown";
    }
}