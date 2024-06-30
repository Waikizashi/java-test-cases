import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ReentrantLock;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.gson.Gson;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A thread-safe class to interact with the CRPT API with rate limiting.
 */
public class CrptApi {
    private static final String API_URL = "https://ismp.crpt.ru/api/v3/lk/documents/create";
    private static final Logger LOGGER = Logger.getLogger(CrptApi.class.getName());

    private final HttpClient httpClient;
    private final Gson gson;
    private final Semaphore semaphore;
    private final ScheduledExecutorService scheduler;
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * Constructs the CrptApi instance with specified rate limits.
     *
     * @param timeUnit the time unit for the rate limit interval
     * @param requestLimit the maximum number of requests per interval
     */
    public CrptApi(TimeUnit timeUnit, int requestLimit) {
        this.httpClient = HttpClient.newBuilder()
                                    .version(HttpClient.Version.HTTP_2)
                                    .build();
        this.gson = new Gson();
        this.semaphore = new Semaphore(requestLimit);
        this.scheduler = Executors.newScheduledThreadPool(1);

        long delay = timeUnit.toMillis(1);
        scheduler.scheduleAtFixedRate(() -> {
            lock.lock();
            try {
                semaphore.release(requestLimit - semaphore.availablePermits());
            } finally {
                lock.unlock();
            }
        }, delay, delay, TimeUnit.MILLISECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));
    }

    /**
     * Creates a document in the CRPT system.
     *
     * @param document the document to be created
     * @param signature the signature for the request
     * @return the response body as a string
     * @throws InterruptedException if the thread is interrupted
     * @throws IOException if an I/O error occurs
     */
    public String createDocument(Document document, String signature) throws InterruptedException, IOException {
        semaphore.acquire();
        try {
            String json = gson.toJson(document);
            HttpRequest request = HttpRequest.newBuilder()
                                             .uri(URI.create(API_URL))
                                             .header("Content-Type", "application/json")
                                             .header("Signature", signature)
                                             .POST(HttpRequest.BodyPublishers.ofString(json))
                                             .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.body();
        } catch (IOException | InterruptedException e) {
            LOGGER.log(Level.SEVERE, "Error while creating document", e);
            throw e;
        }
    }

    /**
     * Shuts down the scheduler and releases resources.
     */
    private void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(60, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException ex) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Data
    @NoArgsConstructor
    public static class Document {
        private Description description;
        private String doc_id;
        private String doc_status;
        private String doc_type = "LP_INTRODUCE_GOODS";
        private boolean importRequest;
        private String owner_inn;
        private String participant_inn;
        private String producer_inn;
        private String production_date;
        private String production_type;
        private Product[] products;
        private String reg_date;
        private String reg_number;

        @Data
        @NoArgsConstructor
        public static class Description {
            private String participantInn;
        }

        @Data
        @NoArgsConstructor
        public static class Product {
            private String certificate_document;
            private String certificate_document_date;
            private String certificate_document_number;
            private String owner_inn;
            private String producer_inn;
            private String production_date;
            private String tnved_code;
            private String uit_code;
            private String uitu_code;
        }
    }
}
