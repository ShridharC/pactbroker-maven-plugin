package com.github.wrm.pact.repository;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

import org.apache.maven.plugin.logging.Log;

import com.github.wrm.pact.domain.PactFile;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

public class BrokerRepositoryProvider implements RepositoryProvider {

    private final String url;
    private final String consumerVersion;
    private final Log log;

    public BrokerRepositoryProvider(String url, String consumerVersion, Log log) {
        this.url = url;
        this.consumerVersion = consumerVersion;
        this.log = log;
    }

    @Override
    public void uploadPacts(List<PactFile> pacts) throws Exception {
        for (PactFile pact : pacts) {
            uploadPact(pact);
        }
    }

    @Override
    public void downloadPacts(String providerId, String tagName, File targetDirectory) throws Exception {
        downloadPactsFromLinks(downloadPactLinks(providerId, tagName), targetDirectory);
    }

    public void downloadPactsFromLinks(List<String> links, File targetDirectory) throws IOException {
        targetDirectory.mkdirs();

        for (String link : links) {
            downloadPactFromLink(targetDirectory, link);
        }
    }

    public List<String> downloadPactLinks(String providerId, String tagName) throws IOException {
        String path = buildDownloadLinksPath(providerId, tagName);

        log.info("Downloading pact links from " + path);

        URL url = new URL(path);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setDoInput(true);
        connection.setDoOutput(true);

        List<String> links = new ArrayList<>();

        if (connection.getResponseCode() != 200) {
            log.error("Downloading pact links failed. Pact Broker answered with: " + connection.getContent());
            return links;
        }

        try (Scanner scanner = new Scanner(connection.getInputStream(), StandardCharsets.UTF_8.displayName())) {
            JsonElement jelement = new JsonParser().parse(scanner.useDelimiter("\\A").next());

            JsonArray asJsonArray = jelement.getAsJsonObject().get("_links").getAsJsonObject().get("pacts")
                    .getAsJsonArray();

            asJsonArray.forEach(element -> {
                links.add(element.getAsJsonObject().get("href").getAsString());
            });
        }

        return links;
    }

    private void uploadPact(PactFile pact) throws IOException {
        String path = buildUploadPath(pact);

        log.info("Uploading pact to " + path);

        URL url = new URL(path);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setDoOutput(true);
        connection.setDoInput(true);
        connection.setRequestMethod("PUT");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("charset", StandardCharsets.UTF_8.displayName());

        byte[] content = Files.readAllBytes(Paths.get(pact.getPath()));
        connection.getOutputStream().write(content);

        if (connection.getResponseCode() > 201) {
            try (Scanner scanner = new Scanner(connection.getInputStream(), StandardCharsets.UTF_8.displayName())) {
                log.error("Uploading failed. Pact Broker answered with: " + scanner.useDelimiter("\\A").next());
            }
        }

        connection.disconnect();
    }

    private void downloadPactFromLink(File targetDirectory, String link) throws MalformedURLException, IOException,
            FileNotFoundException {
        URL url = new URL(link);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setDoInput(true);

        if (connection.getResponseCode() != 200) {
            log.error("Downloading pact failed. Pact Broker answered with: " + connection.getContent());
            return;
        }

        log.info("Downloading pact from " + link);

        try (Scanner scanner = new Scanner(connection.getInputStream(), StandardCharsets.UTF_8.displayName())) {
            String pact = scanner.useDelimiter("\\A").next();

            JsonElement jelement = new JsonParser().parse(pact);

            String provider = jelement.getAsJsonObject().get("provider").getAsJsonObject().get("name").getAsString();
            String consumer = jelement.getAsJsonObject().get("consumer").getAsJsonObject().get("name").getAsString();

            String pactFileName = targetDirectory.getAbsolutePath() + "/" + consumer + "-" + provider + ".json";

            try (PrintWriter printWriter = new PrintWriter(pactFileName)) {
                printWriter.write(pact);
                log.info("Writing pact file to " + pactFileName);
            }
        }
    }

    private String buildUploadPath(PactFile pact) {
        return url + "/pacts/provider/" + pact.getProvider() + "/consumer/" + pact.getConsumer() + "/version/"
                + consumerVersion;
    }

    private String buildDownloadLinksPath(String providerId, String tagName) {
        String downloadUrl = url + "/pacts/provider/" + providerId + "/latest";
        if(tagName != null && !tagName.isEmpty()) {
            return downloadUrl + "/" + tagName;
        }
        else
            return downloadUrl;
    }
}
