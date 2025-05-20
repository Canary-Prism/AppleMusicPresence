package canaryprism.presence.apple.music;

import club.minnced.discord.rpc.DiscordEventHandlers;
import club.minnced.discord.rpc.DiscordRPC;
import club.minnced.discord.rpc.DiscordRichPresence;
import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.tagtraum.japlscript.language.Tdta;
import com.tagtraum.macos.music.Application;
import com.tagtraum.macos.music.Epls;
import com.tagtraum.macos.music.Track;
import dev.dirs.ProjectDirectories;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@CommandLine.Command(subcommands = Main.Set.class)
public class Main implements Runnable {
    
    private static final ProjectDirectories DIRS = ProjectDirectories.from("", "canaryprism", "AppleMusicPresence");
    static {
        System.setProperty("canaryprism.presence.apple.music", Path.of(DIRS.dataDir, "logs").toString());
        ImageIO.scanForPlugins();
    }
    
    public static final int MAXIMUM_IMAGE_CACHE_SIZE = 2048;
    
    private static final Logger log = LoggerFactory.getLogger(Main.class);
    
    private static final DiscordRPC rpc = DiscordRPC.INSTANCE;
    private static final Application app = Application.getInstance();
    
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private final HttpClient client = HttpClient.newHttpClient();
    
    private AsyncLoadingCache<TrackContainer, String> image_cache;
    
    private String fallback_image;
    
    @Override
    public void run() {
        
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
        
        var event_handler = new DiscordEventHandlers();
        event_handler.ready = (user) -> log.info("Ready: {}", user.username);
        
        var application_id_path = config_path.resolve("application_id");
        String application_id;
        try {
            application_id = Files.readString(application_id_path);
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
        
        rpc.Discord_Initialize(application_id, event_handler, false, null);
        
        log.info("initialised");
        
        
        
        this.image_cache = Caffeine.newBuilder()
                .maximumSize(MAXIMUM_IMAGE_CACHE_SIZE)
                .buildAsync((container) -> {
                    if (!(container instanceof RealTrack(var track)))
                        return null;
                    
                    if (track.getArtworks().length == 0)
                        return null;
                    var art = track.getArtworks()[0];
                    var data = art.getRawData().cast(Tdta.class).getTdta();
                    
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
        
        executor.scheduleAtFixedRate(rpc::Discord_RunCallbacks, 0, 2, TimeUnit.SECONDS);
        
        executor.scheduleAtFixedRate(this::checkTrack, 0, 5, TimeUnit.SECONDS);
    }
    
    private void saveImageCache(Path directory) {
        log.info("saving image cache to '{}'", directory);
        try (var files = Files.list(directory)) {
            files.filter(Files::isRegularFile)
                    .forEach(path -> {
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
                    var path = directory.resolve(String.valueOf(container.getTrackId()));
                    try {
                        Files.writeString(path, url, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                    } catch (IOException e) {
                        log.error("failed to write image cache to disk: ", e);
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
                            image_cache.put(new StoredTrack(Integer.parseInt(path.getFileName().toString())), CompletableFuture.completedFuture(url));
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
        if (app.getPlayerState() == Epls.PLAYING) {
            var track = app.getCurrentTrack();
            if (!status_active || track.getId() != last_track_id) {
                last_track_id = track.getId();
                
                updatePresence(track);
                status_active = true;
            }
        } else if (status_active) {
            status_active = false;
            rpc.Discord_ClearPresence();
            log.info("presence cleared");
        }
    }
    
    private void updatePresence(Track track) {
        var presence = new DiscordRichPresence();
        presence.startTimestamp = (System.currentTimeMillis() / 1000) - ((long) app.getPlayerPosition());
        presence.details = track.getName();
        presence.state = track.getArtist();

        var future_image_url = image_cache.get(new RealTrack(track));
        
        presence.largeImageKey = future_image_url.getNow(fallback_image);
        
        presence.largeImageText = track.getAlbum();
        
        log.info("presence image: {}", presence.largeImageKey);
        
        if (!future_image_url.isDone())
            future_image_url.thenRunAsync(() -> updatePresence(app.getCurrentTrack()));
        
        presence.endTimestamp = (System.currentTimeMillis() / 1000) + ((long) (track.getFinish() - app.getPlayerPosition()));
        
        log.info("presence updated: {} - {}", track.getArtist(), track.getName());
        
        rpc.Discord_UpdatePresence(presence);
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