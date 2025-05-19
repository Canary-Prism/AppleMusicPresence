package canaryprism.presence.apple.music;

import com.tagtraum.macos.music.Track;

import java.util.Objects;

public record RealTrack(Track track) implements TrackContainer {
    
    @Override
    public boolean equals(Object o) {
        return (o instanceof TrackContainer container)
                && this.getTrackId() == container.getTrackId();
    }
    
    @Override
    public int hashCode() {
        return Objects.hashCode(this.getTrackId());
    }
}
