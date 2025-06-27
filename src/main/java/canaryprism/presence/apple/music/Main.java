package canaryprism.presence.apple.music;

import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.tagtraum.japlscript.execution.JaplScriptException;
import com.tagtraum.japlscript.language.Tdta;
import com.tagtraum.macos.music.Application;
import com.tagtraum.macos.music.Epls;
import com.tagtraum.macos.music.Track;
import de.jcm.discordgamesdk.ActivityManager;
import de.jcm.discordgamesdk.Core;
import de.jcm.discordgamesdk.CreateParams;
import de.jcm.discordgamesdk.activity.Activity;
import de.jcm.discordgamesdk.activity.ActivityType;
import dev.dirs.ProjectDirectories;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.*;

@CommandLine.Command(subcommands = Main.Set.class)
public class Main implements Runnable {
    
    private static final ProjectDirectories DIRS = ProjectDirectories.from("", "canaryprism", "AppleMusicPresence");
    public static final int LENGTH_LIMIT = 128;
    
    static {
        System.setProperty("canaryprism.presence.apple.music.logdir", Path.of(DIRS.dataDir, "logs").toString());
        ImageIO.scanForPlugins();
    }
    
    public static final int MAXIMUM_IMAGE_CACHE_SIZE = 2048;
    
    private static final Logger log = LoggerFactory.getLogger(Main.class);
    
    private static final Application app = Application.getInstance();
    
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform()
                    .uncaughtExceptionHandler((_, e) -> log.error("exception in scheduled executor: ", e))
                    .factory());
    private final HttpClient client = HttpClient.newHttpClient();
    
    private ActivityManager activity_manager;
    
    private AsyncLoadingCache<TrackContainer, String> image_cache;
    
    private String fallback_image;
    
    @Override
    public void run() {
        
        System.load("/Users/mia/Downloads/discord_game_sdk/discord_game_sdk/lib/aarch64/discord_game_sdk.dylib");
        
        var config_path = Path.of(DIRS.configDir);
        if (Files.notExists(config_path)) {
            log.debug("config path '{}' doesn't exist, creating directories", config_path);
            try {
                Files.createDirectories(config_path);
            } catch (IOException e) {
                throw new RuntimeException("failed to create directory " + config_path, e);
            }
        }
        
        var image_cache_path = Path.of(DIRS.cacheDir, "images");
        if (Files.notExists(image_cache_path)) {
            log.debug("image cache path '{}' doesn't exist, creating directories", image_cache_path);
            try {
                Files.createDirectories(image_cache_path);
            } catch (IOException e) {
                throw new RuntimeException("failed to create directory " + image_cache_path, e);
            }
        }
        
//        var event_handler = new DiscordEventHandlers();
//        event_handler.ready = (user) -> log.info("Ready: {}", user.username);
        
        var application_id_path = config_path.resolve("application_id");
        long application_id;
        try {
            application_id = Long.parseLong(Files.readString(application_id_path));
        } catch (IOException e) {
            throw new RuntimeException("failed to read application id " + application_id_path, e);
        }
        
        log.info("using application id '{}'", application_id);
        
        var api_key_path = config_path.resolve("api_key");
        String api_key;
        try {
            api_key = Files.readString(api_key_path);
        } catch (IOException e) {
            throw new RuntimeException("failed to read api key " + api_key_path, e);
        }
        
        var fallback_image_path = config_path.resolve("fallback_image");
        try {
            this.fallback_image = Files.readString(fallback_image_path);
        } catch (IOException e) {
            log.warn("failed to load fallback image: ", e);
        }
        
        
        var create_params = new CreateParams();
        
        create_params.setClientID(application_id);
        create_params.setFlags(CreateParams.Flags.DEFAULT);

        var core = new Core(create_params);
        
        this.activity_manager = core.activityManager();
        
        log.info("initialised");
        
        
        this.image_cache = Caffeine.newBuilder()
                .maximumSize(MAXIMUM_IMAGE_CACHE_SIZE)
                .buildAsync((container) -> {
                    if (!(container instanceof RealTrack(var track)))
                        return null;
                    
                    log.info("album art not found in cache, generating");
                    
                    if (track.getArtworks().length == 0)
                        return null;
                    var art = track.getArtworks()[0];
                    var data = art.getRawData().cast(Tdta.class).getTdta();
                    
                    data = optimiseImage(data);
                    
//                    var payload = new JSONObject()
//                            .put("key", "6d207e02198a847aa98d0a2a901485a5")
//                            .put("action", "upload")
//                            .put("source", Base64.getEncoder().encodeToString(data));
                    
                    var encoded = Base64.getMimeEncoder().encodeToString(data);
                    var form = new HTTPRequestMultipartBody.Builder()
                            .addPart("source", encoded)
                            .build();
//                    System.out.println(encoded);
                    var request = HttpRequest.newBuilder(URI.create("https://freeimage.host/api/1/upload?key=" + api_key))
                            .header("Content-Type", form.getContentType())
                            .POST(HttpRequest.BodyPublishers.ofByteArray(form.getBody()))
                            .build();
                    
                    log.info("uploading image for track {}", track.getName());
                    
                    var http_response = client.send(request, HttpResponse.BodyHandlers.ofString());
                    
                    var response = new JSONObject(http_response.body());
                    
                    
                    return response.getJSONObject("image").getString("url");
                });
        
        loadImageCache(image_cache_path);
        
        Runtime.getRuntime().addShutdownHook(new Thread(() -> saveImageCache(image_cache_path)));
        
        
        executor.scheduleAtFixedRate(core::runCallbacks, 0, 2, TimeUnit.SECONDS);
        
        executor.scheduleAtFixedRate(() -> {
            try {
                this.checkTrack();
            } catch (Exception e) {
                log.error("exception while checking track: ", e);
            }
        }, 0, 5, TimeUnit.SECONDS);
        
        executor.scheduleAtFixedRate(() -> saveImageCache(image_cache_path), 0, 10, TimeUnit.MINUTES);
    }
    
    private byte[] optimiseImage(byte[] data) throws IOException {
        var art = ImageIO.read(new ByteArrayInputStream(data));
        
        var max_dimension = Math.max(art.getWidth(), art.getHeight());
        
        var image = new BufferedImage(max_dimension, max_dimension, BufferedImage.TYPE_INT_ARGB);
        
        var g = image.getGraphics();
        
        var blurred = blur(art);
        
        {
            var blurred_min_dimension = Math.min(blurred.getWidth(), blurred.getHeight());
//            log.info("blurred min dimension: {}", blurred_min_dimension);
            var scale = (double) max_dimension / blurred_min_dimension;
            var scaled_width = (int) (blurred.getWidth() * scale);
            var scaled_height = (int) (blurred.getHeight() * scale);
            var scaled = blurred.getScaledInstance(scaled_width, scaled_height, BufferedImage.SCALE_SMOOTH);
            g.drawImage(
                    scaled,
                    (max_dimension - scaled_width) / 2,
                    (max_dimension - scaled_height) / 2,
                    null
            );
        }
        
        g.drawImage(art, (max_dimension - art.getWidth()) / 2, (max_dimension - art.getHeight()) / 2, null);
        try (var baos = new ByteArrayOutputStream()) {
            
            ImageIO.write(image, "png", baos);
            
            return baos.toByteArray();
        }
    }
    
    private BufferedImage blur(BufferedImage image) {
        int radius = 100;
        
        var kernels = makeKernels(radius);
        
        var hop = new ConvolveOp(kernels.horizontal, ConvolveOp.EDGE_ZERO_FILL, null);
        image = hop.filter(image, null);
        
        var vop = new ConvolveOp(kernels.vertical, ConvolveOp.EDGE_ZERO_FILL, null);
        image = vop.filter(image, null);
        
//        return i;
        return image.getSubimage(radius, radius, image.getWidth() - radius * 2, image.getHeight() - radius * 2);
    }
    
    record KernelTuple(Kernel horizontal, Kernel vertical) {}
    
    private static KernelTuple makeKernels(float radius) {
        int r = (int) Math.ceil(radius);
        int rows = r * 2 + 1;
        float[] matrix = new float[rows];
        float sigma = radius / 3;
        float sigma22 = 2 * sigma * sigma;
        var sigmaPi2 = 2 * Math.PI * sigma;
        float sqrtSigmaPi2 = (float) Math.sqrt(sigmaPi2);
        float radius2 = radius * radius;
        float total = 0;
        int index = 0;
        for (int row = -r; row <= r; row++) {
            float distance = row * row;
            if (distance > radius2)
                matrix[index] = 0;
            else
                matrix[index] = (float) Math.exp(-(distance)/sigma22) / sqrtSigmaPi2;
            total += matrix[index];
            index++;
        }
        for (int i = 0; i < rows; i++)
            matrix[i] /= total;
        
        return new KernelTuple(new Kernel(rows, 1, matrix), new Kernel(1, rows, matrix));
    }
    
    private void saveImageCache(Path directory) {
        log.info("saving image cache to '{}'", directory);
        try (var files = Files.list(directory)) {
            files.filter(Files::isRegularFile)
                    .forEach((path) -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            log.error("failed to clear cache directory: ", e);
                        }
                    });
        } catch (IOException e) {
            log.error("failed to access image cache path '{}': ", directory, e);
        }
        image_cache.synchronous()
                .asMap()
                .forEach((container, url) -> {
                    try {
                        var path = directory.resolve(String.valueOf(container.getTrackId()));
                        Files.writeString(path, url, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                    } catch (IOException e) {
                        log.error("failed to write image cache to disk: ", e);
                    } catch (JaplScriptException e) {
                        log.error("failed to get track id for {}", container);
                    }
                });
    }
    
    private void loadImageCache(Path directory) {
        log.info("loading image cache from '{}'", directory);
        try (var files = Files.list(directory)) {
            files.filter(Files::isRegularFile)
                    .forEach((path) -> {
                        try {
                            var url = Files.readString(path);
                            image_cache.put(new StoredTrack(path.getFileName().toString()), CompletableFuture.completedFuture(url));
                        } catch (IOException e) {
                            log.error("failed to load image cache from disk: ", e);
                        }
                    });
        } catch (IOException e) {
            log.error("failed to access image cache path '{}': ", directory, e);
        }
    }
    
//    private byte[] toWebp(byte[] data) {
//        try (var baos = new ByteArrayOutputStream()) {
//            var image = ImageIO.read(new ByteArrayInputStream(data));
//            var scaled_image = image.getScaledInstance(128, 128, Image.SCALE_SMOOTH);
//
//            image = new BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB);
//            image.getGraphics().drawImage(scaled_image, 0, 0, null);
//
//            ImageIO.write(image, "png", baos);
//
//            return baos.toByteArray();
//
//        } catch (IOException e) {
//            throw new RuntimeException(e);
//        }
//    }
    
    
    private volatile int last_track_id;
    private volatile boolean status_active = false;
    
    private void checkTrack() {
        checkTrack(false);
    }
    private void checkTrack(boolean force) {
        if (app.getPlayerState() == Epls.PLAYING) {
            var track = app.getCurrentTrack();
            if (!status_active || track.getId() != last_track_id || force) {
                last_track_id = track.getId();
                
                synchronized (this) {
                    if (track_end_check != null)
                        track_end_check.cancel(false);
                    track_end_check = null;
                }
                
                updatePresence(track);
                status_active = true;
            }
        } else if (status_active) {
            status_active = false;
            
            activity_manager.clearActivity();
            
            synchronized (this) {
                if (track_end_check != null)
                    track_end_check.cancel(false);
                track_end_check = null;
            }
            
            log.info("presence cleared");
        }
    }
    
    private volatile ScheduledFuture<?> track_end_check;
    
    private void updatePresence(Track track) {
        var activity = new Activity();
        
        activity.setType(ActivityType.LISTENING);
        
        var timestamps = activity.timestamps();
        
        var now = Instant.now();
        
        timestamps.setStart(now.minus(Duration.ofMillis(((long) (app.getPlayerPosition() * 1000)))));
        
        var remaining = (long)((track.getFinish() - app.getPlayerPosition()) * 1000);
        
        timestamps.setEnd(now.plus(Duration.ofMillis(remaining)));
        
        
        activity.setDetails(track.getName());
        activity.setState(Optional.of(track.getArtist())
                .filter((e) -> !e.isEmpty())
                .map((e) -> e.substring(0, Math.min(e.length(), LENGTH_LIMIT)))
                .orElse(null));
        
        var assets = activity.assets();
        
        var future_image_url = image_cache.get(new RealTrack(track));
        
        var image_url = future_image_url.getNow(fallback_image);
        
        assets.setLargeImage(image_url);
        assets.setLargeText(Optional.of(track.getAlbum())
                .filter((e) -> !e.isEmpty())
                .map((e) -> e.substring(0, Math.min(e.length(), LENGTH_LIMIT)))
                .orElse(null));
        
        log.info("presence image: {}", image_url);
        
        if (!future_image_url.isDone()) {
            future_image_url.thenRunAsync(() -> checkTrack(true));
        }
        
        
        log.info("presence updated: {} - {}", track.getArtist(), track.getName());
        
        activity_manager.updateActivity(activity);
        
        synchronized (this) {
            if (track_end_check == null) {
                track_end_check = executor.schedule(() -> checkTrack(true), remaining + 1000, TimeUnit.MILLISECONDS);
            }
        }
    }
    
    @CommandLine.Command(
            name = "set",
            subcommands = { Set.ApplicationId.class, Set.ApiKey.class, Set.FallbackImage.class }
    )
    static class Set {
        @CommandLine.Command(name = "application_id")
        static class ApplicationId implements Runnable {
            
            @CommandLine.Parameters(index = "0")
            private String application_id;
            
            @Override
            public void run() {
                write("application_id", application_id);
            }
        }
        @CommandLine.Command(name = "api_key")
        static class ApiKey implements Runnable {
            
            @CommandLine.Parameters(index = "0")
            private String api_key;
            
            @Override
            public void run() {
                write("api_key", api_key);
            }
        }
        @CommandLine.Command(name = "fallback_image")
        static class FallbackImage implements Runnable {
            
            @CommandLine.Parameters(index = "0")
            private String fallback_image;
            
            @Override
            public void run() {
                write("fallback_image", fallback_image);
            }
        }
        
        private static void write(String file_name, String data) {
            var path = Path.of(DIRS.configDir, file_name);
            try {
                Files.writeString(path, data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                System.out.println("wrote " + file_name + " to " + path);
            } catch (IOException e) {
                throw new RuntimeException("failed to write " + file_name + " to " + path, e);
            }
        }
    }
    
    public static void main(String[] args) {
        new CommandLine(new Main()).execute(args);
    }
}