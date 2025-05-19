package canaryprism.presence.apple.music;

import com.tagtraum.macos.music.Track;

import java.util.Objects;

public record TrackContainer(Track track) {
    
    @Override
    public boolean equals(Object o) {
        return o instanceof TrackContainer(var other_track)
                && track.getId() == other_track.getId();
    }
    
    @Override
    public int hashCode() {
        return Objects.hashCode(track.getId());
    }
}
