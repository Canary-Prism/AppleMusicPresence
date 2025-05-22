package canaryprism.presence.apple.music;

public sealed interface TrackContainer permits RealTrack, StoredTrack {
    
    default String getTrackId() {
        return switch (this) {
            case RealTrack(var track) -> String.valueOf(track.getDatabaseId());
            case StoredTrack(var id) -> id;
        };
    }
}
