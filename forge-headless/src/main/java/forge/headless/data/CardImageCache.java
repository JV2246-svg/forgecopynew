package forge.headless.data;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * On-demand card image fetcher with a permanent disk cache (docs/DATA.md).
 * Never bulk-downloads; single fetches with compliant headers, throttled well
 * under Scryfall's 10 req/s limit. Cache keys include the URI's version query
 * param, so an entry only invalidates when Scryfall re-images the card.
 * Images are stored (and must be rendered) uncropped per the API terms.
 */
public final class CardImageCache {
    private static final long MIN_REQUEST_INTERVAL_MS = 120;

    private final Path cacheDir;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
    private long lastRequestAt;

    public CardImageCache(final Path cacheDir) throws IOException {
        this.cacheDir = cacheDir;
        Files.createDirectories(cacheDir);
    }

    /**
     * Returns the local path of the image for the given Scryfall image URI,
     * fetching and caching it on first use.
     */
    public Path get(final String scryfallId, final String imageUri) throws IOException, InterruptedException {
        final Path file = cacheDir.resolve(fileNameFor(scryfallId, imageUri));
        if (Files.exists(file)) {
            return file;
        }
        throttle();
        final HttpRequest request = HttpRequest.newBuilder(URI.create(imageUri))
                .header("User-Agent", ScryfallBulkSync.USER_AGENT)
                .header("Accept", "image/jpeg;q=0.9,image/*;q=0.8,*/*;q=0.5")
                .build();
        final HttpResponse<Path> response = http.send(request,
                HttpResponse.BodyHandlers.ofFile(Files.createTempFile(cacheDir, "dl", ".part")));
        if (response.statusCode() != 200) {
            Files.deleteIfExists(response.body());
            throw new IOException("image fetch returned HTTP " + response.statusCode() + " for " + imageUri);
        }
        Files.move(response.body(), file, StandardCopyOption.REPLACE_EXISTING);
        return file;
    }

    private static String fileNameFor(final String scryfallId, final String imageUri) {
        // include the version timestamp from the URI query so re-imaged cards refresh
        final int q = imageUri.indexOf('?');
        final String version = q >= 0 ? imageUri.substring(q + 1).replaceAll("[^0-9]", "") : "0";
        return scryfallId + "-" + version + ".jpg";
    }

    private synchronized void throttle() throws InterruptedException {
        final long now = System.currentTimeMillis();
        final long wait = lastRequestAt + MIN_REQUEST_INTERVAL_MS - now;
        if (wait > 0) {
            Thread.sleep(wait);
        }
        lastRequestAt = System.currentTimeMillis();
    }
}
