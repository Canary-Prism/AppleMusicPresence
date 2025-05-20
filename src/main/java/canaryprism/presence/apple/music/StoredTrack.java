package canaryprism.presence.apple.music;

import java.util.Objects;

public record StoredTrack(String id) implements TrackContainer {
    
    @Override
    public boolean equals(Object o) {
        return (o instanceof TrackContainer container)
                && Objects.equals(this.getTrackId(), container.getTrackId());
    }
    
    @Override
    public int hashCode() {
        return Objects.hashCode(this.getTrackId());
    }
}
