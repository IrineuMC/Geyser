/*
 * Copyright (c) 2019-2022 GeyserMC. http://geysermc.org
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 * @author GeyserMC
 * @link https://github.com/GeyserMC/Geyser
 */

package org.geysermc.geyser.text;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.network.MinecraftProtocol;
import org.geysermc.geyser.util.FileUtils;
import org.geysermc.geyser.util.WebUtils;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipFile;

public class MinecraftLocale {

    public static final Map<String, Map<String, String>> LOCALE_MAPPINGS = new HashMap<>();

    private static final Map<String, Asset> ASSET_MAP = new HashMap<>();

    private static VersionDownload clientJarInfo;

    static {
        // Create the locales folder
        File localesFolder = GeyserImpl.getInstance().getBootstrap().getConfigFolder().resolve("locales").toFile();
        //noinspection ResultOfMethodCallIgnored
        localesFolder.mkdir();

        // Download the latest asset list and cache it
        generateAssetCache().whenComplete((aVoid, ex) -> downloadAndLoadLocale(GeyserLocale.getDefaultLocale()));
    }

    /**
     * Fetch the latest versions asset cache from Mojang so we can grab the locale files later
     */
    private static CompletableFuture<Void> generateAssetCache() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Get the version manifest from Mojang
                VersionManifest versionManifest = GeyserImpl.JSON_MAPPER.readValue(WebUtils.getBody("https://launchermeta.mojang.com/mc/game/version_manifest.json"), VersionManifest.class);

                // Get the url for the latest version of the games manifest
                String latestInfoURL = "";
                for (Version version : versionManifest.getVersions()) {
                    if (version.getId().equals(MinecraftProtocol.getJavaCodec().getMinecraftVersion())) {
                        latestInfoURL = version.getUrl();
                        break;
                    }
                }

                // Make sure we definitely got a version
                if (latestInfoURL.isEmpty()) {
                    throw new Exception(GeyserLocale.getLocaleStringLog("geyser.locale.fail.latest_version"));
                }

                // Get the individual version manifest
                VersionInfo versionInfo = GeyserImpl.JSON_MAPPER.readValue(WebUtils.getBody(latestInfoURL), VersionInfo.class);

                // Get the client jar for use when downloading the en_us locale
                GeyserImpl.getInstance().getLogger().debug(GeyserImpl.JSON_MAPPER.writeValueAsString(versionInfo.getDownloads()));
                clientJarInfo = versionInfo.getDownloads().get("client");
                GeyserImpl.getInstance().getLogger().debug(GeyserImpl.JSON_MAPPER.writeValueAsString(clientJarInfo));

                // Get the assets list
                JsonNode assets = GeyserImpl.JSON_MAPPER.readTree(WebUtils.getBody(versionInfo.getAssetIndex().getUrl())).get("objects");

                // Put each asset into an array for use later
                Iterator<Map.Entry<String, JsonNode>> assetIterator = assets.fields();
                while (assetIterator.hasNext()) {
                    Map.Entry<String, JsonNode> entry = assetIterator.next();
                    if (!entry.getKey().startsWith("minecraft/lang/")) {
                        // No need to cache non-language assets as we don't use them
                        continue;
                    }

                    Asset asset = GeyserImpl.JSON_MAPPER.treeToValue(entry.getValue(), Asset.class);
                    ASSET_MAP.put(entry.getKey(), asset);
                }
            } catch (Exception e) {
                GeyserImpl.getInstance().getLogger().error(GeyserLocale.getLocaleStringLog("geyser.locale.fail.asset_cache", (!e.getMessage().isEmpty() ? e.getMessage() : e.getStackTrace())));
            }
            return null;
        });
    }

    /**
     * Downloads a locale from Mojang if its not already loaded
     *
     * @param locale Locale to download and load
     */
    public static void downloadAndLoadLocale(String locale) {
        locale = locale.toLowerCase(Locale.ROOT);
        if (locale.equals("nb_no")) {
            // Different locale code - https://minecraft.fandom.com/wiki/Language
            locale = "no_no";
        }

        // Check the locale isn't already loaded
        if (!ASSET_MAP.containsKey("minecraft/lang/" + locale + ".json") && !locale.equals("en_us")) {
            if (loadLocale(locale)) {
                GeyserImpl.getInstance().getLogger().debug("Loaded locale locally while not being in asset map: " + locale);
            } else {
                GeyserImpl.getInstance().getLogger().warning(GeyserLocale.getLocaleStringLog("geyser.locale.fail.invalid", locale));
            }
            return;
        }

        GeyserImpl.getInstance().getLogger().debug("Downloading and loading locale: " + locale);

        downloadLocale(locale);
        if (!loadLocale(locale)) {
            GeyserImpl.getInstance().getLogger().warning(GeyserLocale.getLocaleStringLog("geyser.locale.fail.missing", locale));
        }
    }

    /**
     * Downloads the specified locale if its not already downloaded
     *
     * @param locale Locale to download
     */
    private static void downloadLocale(String locale) {
        File localeFile = GeyserImpl.getInstance().getBootstrap().getConfigFolder().resolve("locales/" + locale + ".json").toFile();

        // Check if we have already downloaded the locale file
        if (localeFile.exists()) {
            String curHash = "";
            String targetHash;

            if (locale.equals("en_us")) {
                try {
                    File hashFile = GeyserImpl.getInstance().getBootstrap().getConfigFolder().resolve("locales/en_us.hash").toFile();
                    if (hashFile.exists()) {
                        try (BufferedReader br = new BufferedReader(new FileReader(hashFile))) {
                            curHash = br.readLine().trim();
                        }
                    }
                } catch (IOException ignored) { }

                if (clientJarInfo == null) {
                    // Likely failed to download
                    GeyserImpl.getInstance().getLogger().debug("Skipping en_US hash check as client jar is null.");
                    return;
                }
                targetHash = clientJarInfo.getSha1();
            } else {
                curHash = byteArrayToHexString(FileUtils.calculateSHA1(localeFile));
                targetHash = ASSET_MAP.get("minecraft/lang/" + locale + ".json").getHash();
            }

            if (!curHash.equals(targetHash)) {
                GeyserImpl.getInstance().getLogger().debug("Locale out of date; re-downloading: " + locale);
            } else {
                GeyserImpl.getInstance().getLogger().debug("Locale already downloaded and up-to date: " + locale);
                return;
            }
        }

        // Create the en_us locale
        if (locale.equals("en_us")) {
            downloadEN_US(localeFile);

            return;
        }

        try {
            // Get the hash and download the locale
            String hash = ASSET_MAP.get("minecraft/lang/" + locale + ".json").getHash();
            WebUtils.downloadFile("https://resources.download.minecraft.net/" + hash.substring(0, 2) + "/" + hash, localeFile.toString());
        } catch (Exception e) {
            GeyserImpl.getInstance().getLogger().error("Unable to download locale file hash", e);
        }
    }

    /**
     * Loads a locale already downloaded, if the file doesn't exist it just logs a warning
     *
     * @param locale Locale to load
     */
    private static boolean loadLocale(String locale) {
        File localeFile = GeyserImpl.getInstance().getBootstrap().getConfigFolder().resolve("locales/" + locale + ".json").toFile();

        // Load the locale
        if (localeFile.exists()) {
            // Read the localefile
            InputStream localeStream;
            try {
                localeStream = new FileInputStream(localeFile);
            } catch (FileNotFoundException e) {
                throw new AssertionError(GeyserLocale.getLocaleStringLog("geyser.locale.fail.file", locale, e.getMessage()));
            }

            // Parse the file as json
            JsonNode localeObj;
            try {
                localeObj = GeyserImpl.JSON_MAPPER.readTree(localeStream);
            } catch (Exception e) {
                throw new AssertionError(GeyserLocale.getLocaleStringLog("geyser.locale.fail.json", locale), e);
            }

            // Parse all the locale fields
            Iterator<Map.Entry<String, JsonNode>> localeIterator = localeObj.fields();
            Map<String, String> langMap = new HashMap<>();
            while (localeIterator.hasNext()) {
                Map.Entry<String, JsonNode> entry = localeIterator.next();
                langMap.put(entry.getKey(), entry.getValue().asText());
            }

            String bedrockLocale = locale.toLowerCase(Locale.ROOT);
            if (bedrockLocale.equals("no_no")) {
                // Store this locale under the Bedrock locale so we don't need to do this check over and over
                bedrockLocale = "nb_no";
            }

            // Insert the locale into the mappings
            LOCALE_MAPPINGS.put(bedrockLocale, langMap);

            try {
                localeStream.close();
            } catch (IOException e) {
                throw new AssertionError(GeyserLocale.getLocaleStringLog("geyser.locale.fail.file", locale, e.getMessage()));
            }
            return true;
        } else {
            return false;
        }
    }

    /**
     * Download then en_us locale by downloading the server jar and extracting it from there.
     *
     * @param localeFile File to save the locale to
     */
    private static void downloadEN_US(File localeFile) {
        try {
            // Let the user know we are downloading the JAR
            GeyserImpl.getInstance().getLogger().info(GeyserLocale.getLocaleStringLog("geyser.locale.download.en_us"));
            GeyserImpl.getInstance().getLogger().debug("Download URL: " + clientJarInfo.getUrl());

            // Download the smallest JAR (client or server)
            Path tmpFilePath = GeyserImpl.getInstance().getBootstrap().getConfigFolder().resolve("tmp_locale.jar");
            WebUtils.downloadFile(clientJarInfo.getUrl(), tmpFilePath.toString());

            // Load in the JAR as a zip and extract the file
            try (ZipFile localeJar = new ZipFile(tmpFilePath.toString())) {
                try (InputStream fileStream = localeJar.getInputStream(localeJar.getEntry("assets/minecraft/lang/en_us.json"))) {
                    try (FileOutputStream outStream = new FileOutputStream(localeFile)) {

                        // Write the file to the locale dir
                        byte[] buf = new byte[fileStream.available()];
                        int length;
                        while ((length = fileStream.read(buf)) != -1) {
                            outStream.write(buf, 0, length);
                        }

                        // Flush all changes to disk and cleanup
                        outStream.flush();
                    }
                }
            }

            // Store the latest jar hash
            FileUtils.writeFile(GeyserImpl.getInstance().getBootstrap().getConfigFolder().resolve("locales/en_us.hash").toString(), clientJarInfo.getSha1().toCharArray());

            // Delete the nolonger needed client/server jar
            Files.delete(tmpFilePath);

            GeyserImpl.getInstance().getLogger().info(GeyserLocale.getLocaleStringLog("geyser.locale.download.en_us.done"));
        } catch (Exception e) {
            GeyserImpl.getInstance().getLogger().error(GeyserLocale.getLocaleStringLog("geyser.locale.fail.en_us"), e);
        }
    }

    /**
     * Translate the given language string into the given locale, or falls back to the default locale
     *
     * @param messageText Language string to translate
     * @param locale Locale to translate to
     * @return Translated string or the original message if it was not found in the given locale
     */
    public static String getLocaleString(String messageText, String locale) {
        Map<String, String> localeStrings = LOCALE_MAPPINGS.get(locale.toLowerCase(Locale.ROOT));
        if (localeStrings == null) {
            localeStrings = LOCALE_MAPPINGS.get(GeyserLocale.getDefaultLocale());
            if (localeStrings == null) {
                // Don't cause a NPE if the locale is STILL missing
                GeyserImpl.getInstance().getLogger().debug("MISSING DEFAULT LOCALE: " + GeyserLocale.getDefaultLocale());
                return messageText;
            }
        }

        return localeStrings.getOrDefault(messageText, messageText);
    }

    /**
     * Convert a byte array into a hex string
     *
     * @param b Byte array to convert
     * @return The hex representation of the given byte array
     */
    private static String byteArrayToHexString(byte[] b) {
        StringBuilder result = new StringBuilder();
        for (byte value : b) {
            result.append(Integer.toString((value & 0xff) + 0x100, 16).substring(1));
        }
        return result.toString();
    }

    public static void init() {
        // no-op
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Getter
    static class VersionManifest {
        @JsonProperty("latest")
        private LatestVersion latestVersion;

        @JsonProperty("versions")
        private List<Version> versions;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Getter
    static class LatestVersion {
        @JsonProperty("release")
        private String release;

        @JsonProperty("snapshot")
        private String snapshot;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Getter
    static class Version {
        @JsonProperty("id")
        private String id;

        @JsonProperty("type")
        private String type;

        @JsonProperty("url")
        private String url;

        @JsonProperty("time")
        private String time;

        @JsonProperty("releaseTime")
        private String releaseTime;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Getter
    static class VersionInfo {
        @JsonProperty("id")
        private String id;

        @JsonProperty("type")
        private String type;

        @JsonProperty("time")
        private String time;

        @JsonProperty("releaseTime")
        private String releaseTime;

        @JsonProperty("assetIndex")
        private AssetIndex assetIndex;

        @JsonProperty("downloads")
        private Map<String, VersionDownload> downloads;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Getter
    static class VersionDownload {
        @JsonProperty("sha1")
        private String sha1;

        @JsonProperty("size")
        private int size;

        @JsonProperty("url")
        private String url;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Getter
    static class AssetIndex {
        @JsonProperty("id")
        private String id;

        @JsonProperty("sha1")
        private String sha1;

        @JsonProperty("size")
        private int size;

        @JsonProperty("totalSize")
        private int totalSize;

        @JsonProperty("url")
        private String url;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Getter
    static class Asset {
        @JsonProperty("hash")
        private String hash;

        @JsonProperty("size")
        private int size;
    }
}