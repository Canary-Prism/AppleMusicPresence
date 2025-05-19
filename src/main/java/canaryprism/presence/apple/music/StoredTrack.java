package canaryprism.presence.apple.music;

import java.util.Objects;

public record StoredTrack(int id) implements TrackContainer {
    
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
